package com.string1225.pocketpilot.update

import android.content.Context
import androidx.core.content.edit
import com.string1225.pocketpilot.BuildConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class GitHubReleaseUpdateManager private constructor(
    context: Context,
    private val source: GitHubReleaseSource,
    private val verifier: UpdateApkVerifier,
    private val cache: UpdateCache,
    private val installer: UpdateInstallLauncher,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val operationMutex = Mutex()
    private val readyLock = Any()
    private var resolvedRelease: ResolvedUpdateRelease? = null
    private var verifiedApk: VerifiedUpdateApk? = null
    private val installed = runCatching { verifier.installedIdentity() }.getOrElse {
        InstalledAppIdentity(
            packageName = context.applicationContext.packageName,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            signingCertificateSha256 = emptySet(),
        )
    }

    private val mutableState = MutableStateFlow(
        UpdateState(
            currentVersionName = installed.versionName,
            currentVersionCode = installed.versionCode,
        ),
    )
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    constructor(context: Context) : this(
        context = context.applicationContext,
        source = OkHttpGitHubReleaseSource(),
        verifier = AndroidUpdateApkVerifier(context.applicationContext),
        cache = UpdateCache(context.applicationContext),
        installer = AndroidUpdateInstallLauncher(context.applicationContext, UpdateCache(context.applicationContext)),
        ioDispatcher = Dispatchers.IO,
        clock = System::currentTimeMillis,
    )

    suspend fun check(force: Boolean = false): UpdateCheckResult = operationMutex.withLock {
        val now = clock()
        val nextCheckAt = preferences.getLong(KEY_NEXT_AUTOMATIC_CHECK_AT, 0L)
        if (!force && !UpdateCheckThrottle.shouldCheck(now, nextCheckAt)) {
            mutableState.value = baseState(
                phase = UpdatePhase.THROTTLED,
                nextCheckAtMillis = nextCheckAt,
            )
            return@withLock UpdateCheckResult.Throttled(nextCheckAt)
        }
        if (!force) {
            preferences.edit {
                putLong(KEY_NEXT_AUTOMATIC_CHECK_AT, safeAdd(now, UpdateCheckThrottle.FAILURE_RETRY_MILLIS))
            }
        }
        mutableState.value = baseState(UpdatePhase.CHECKING)
        try {
            val release = source.latestRelease()
            val previousRelease = resolvedRelease
            resolvedRelease = release
            val existing = synchronized(readyLock) { verifiedApk }
            val reusableReadyApk = existing?.takeIf {
                previousRelease?.public?.tagName == release.public.tagName &&
                    runCatching { cache.requireOwnedApk(it.file).length() == it.sizeBytes }.getOrDefault(false)
            }
            if (reusableReadyApk == null) synchronized(readyLock) { verifiedApk = null }
            preferences.edit {
                putLong(KEY_NEXT_AUTOMATIC_CHECK_AT, safeAdd(now, UpdateCheckThrottle.SUCCESS_INTERVAL_MILLIS))
            }
            if (GitHubReleasePolicy.isNewer(release.public.versionName, installed.versionName)) {
                mutableState.value = if (reusableReadyApk != null) {
                    baseState(
                        UpdatePhase.READY_TO_INSTALL,
                        release = release.public,
                        bytesDownloaded = reusableReadyApk.sizeBytes,
                        totalBytes = reusableReadyApk.sizeBytes,
                    )
                } else {
                    baseState(UpdatePhase.AVAILABLE, release = release.public)
                }
                UpdateCheckResult.Available(release.public)
            } else {
                mutableState.value = baseState(UpdatePhase.UP_TO_DATE, release = release.public)
                UpdateCheckResult.UpToDate
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val message = safeErrorMessage(error, "Unable to check for updates")
            mutableState.value = baseState(UpdatePhase.ERROR, errorMessage = message)
            UpdateCheckResult.Failed(message)
        }
    }

    suspend fun download(): DownloadResult = operationMutex.withLock {
        val release = resolvedRelease
        if (release == null || !GitHubReleasePolicy.isNewer(release.public.versionName, installed.versionName)) {
            val message = "No newer release is ready to download"
            mutableState.value = baseState(UpdatePhase.ERROR, errorMessage = message)
            return@withLock DownloadResult.Failed(message)
        }
        val existing = synchronized(readyLock) { verifiedApk }
        if (
            existing != null &&
            mutableState.value.phase in setOf(
                UpdatePhase.READY_TO_INSTALL,
                UpdatePhase.INSTALL_PERMISSION_REQUIRED,
                UpdatePhase.INSTALL_LAUNCHED,
            ) &&
            runCatching { cache.requireOwnedApk(existing.file).length() == existing.sizeBytes }.getOrDefault(false)
        ) {
            return@withLock DownloadResult.Ready(release.public)
        }
        synchronized(readyLock) { verifiedApk = null }
        val destination = try {
            cache.prepare(release.public.versionName)
        } catch (error: Throwable) {
            val message = safeErrorMessage(error, "Unable to prepare update storage")
            mutableState.value = baseState(UpdatePhase.ERROR, release = release.public, errorMessage = message)
            return@withLock DownloadResult.Failed(message)
        }
        mutableState.value = baseState(
            phase = UpdatePhase.DOWNLOADING,
            release = release.public,
            totalBytes = release.apkAsset.sizeBytes,
        )
        try {
            val downloaded = source.download(release, destination) { bytes, total ->
                mutableState.value = baseState(
                    phase = UpdatePhase.DOWNLOADING,
                    release = release.public,
                    bytesDownloaded = bytes,
                    totalBytes = total ?: release.apkAsset.sizeBytes,
                )
            }
            val verified = withContext(ioDispatcher) {
                val ownedFile = cache.requireOwnedApk(downloaded.file)
                val result = verifier.verify(
                    file = ownedFile,
                    expectedSha256 = downloaded.expectedSha256,
                    expectedSizeBytes = downloaded.sizeBytes,
                    expectedVersionName = release.public.versionName,
                )
                check(ownedFile.setReadable(true, true)) { "Unable to secure downloaded update" }
                check(ownedFile.setWritable(false, false)) { "Unable to secure downloaded update" }
                result
            }
            synchronized(readyLock) { verifiedApk = verified }
            mutableState.value = baseState(
                phase = UpdatePhase.READY_TO_INSTALL,
                release = release.public,
                bytesDownloaded = verified.sizeBytes,
                totalBytes = verified.sizeBytes,
            )
            DownloadResult.Ready(release.public)
        } catch (error: Throwable) {
            destination.delete()
            synchronized(readyLock) { verifiedApk = null }
            if (error is CancellationException) throw error
            val message = safeErrorMessage(error, "Unable to download this update")
            mutableState.value = baseState(UpdatePhase.ERROR, release = release.public, errorMessage = message)
            DownloadResult.Failed(message)
        }
    }

    suspend fun install(): InstallLaunchResult = operationMutex.withLock {
        val verified = synchronized(readyLock) { verifiedApk }
            ?: return@withLock InstallLaunchResult.NotReady
        val release = resolvedRelease?.public
        return@withLock try {
            val reverified = withContext(ioDispatcher) {
                val ownedFile = cache.requireOwnedApk(verified.file)
                verifier.verify(
                    file = ownedFile,
                    expectedSha256 = verified.sha256,
                    expectedSizeBytes = verified.sizeBytes,
                    expectedVersionName = release?.versionName
                        ?: throw IllegalStateException("Update release information is unavailable"),
                )
            }
            check(reverified.versionCode == verified.versionCode) { "Downloaded update changed after verification" }
            if (!installer.canRequestPackageInstalls()) {
                mutableState.value = baseState(UpdatePhase.INSTALL_PERMISSION_REQUIRED, release = release)
                InstallLaunchResult.PermissionRequired
            } else if (installer.launchInstaller(reverified.file)) {
                mutableState.value = baseState(UpdatePhase.INSTALL_LAUNCHED, release = release)
                InstallLaunchResult.Launched
            } else {
                val message = "Android package installer is unavailable"
                mutableState.value = baseState(UpdatePhase.ERROR, release = release, errorMessage = message)
                InstallLaunchResult.Failed(message)
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val message = safeErrorMessage(error, "Unable to open the Android package installer")
            mutableState.value = baseState(UpdatePhase.ERROR, release = release, errorMessage = message)
            InstallLaunchResult.Failed(message)
        }
    }

    fun openUnknownSourcesSettings(): Boolean = installer.openUnknownSourcesSettings()

    private fun baseState(
        phase: UpdatePhase,
        release: UpdateRelease? = null,
        bytesDownloaded: Long = 0L,
        totalBytes: Long? = null,
        errorMessage: String? = null,
        nextCheckAtMillis: Long? = null,
    ): UpdateState = UpdateState(
        phase = phase,
        currentVersionName = installed.versionName,
        currentVersionCode = installed.versionCode,
        release = release,
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes,
        errorMessage = errorMessage,
        nextCheckAtMillis = nextCheckAtMillis,
    )

    companion object {
        private const val PREFERENCES_NAME = "pocketpilot.update"
        private const val KEY_NEXT_AUTOMATIC_CHECK_AT = "next_automatic_check_at"

        internal fun createForTest(
            context: Context,
            source: GitHubReleaseSource,
            verifier: UpdateApkVerifier,
            cache: UpdateCache = UpdateCache(context.applicationContext),
            installer: UpdateInstallLauncher,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            clock: () -> Long,
        ): GitHubReleaseUpdateManager = GitHubReleaseUpdateManager(
            context.applicationContext,
            source,
            verifier,
            cache,
            installer,
            ioDispatcher,
            clock,
        )

        private fun safeAdd(left: Long, right: Long): Long =
            if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

        private fun safeErrorMessage(error: Throwable, fallback: String): String = when (error) {
            is UpdateException -> error.message?.takeIf(String::isNotBlank) ?: fallback
            is IllegalArgumentException, is IllegalStateException -> error.message?.takeIf(String::isNotBlank) ?: fallback
            else -> fallback
        }.take(256)
    }
}
