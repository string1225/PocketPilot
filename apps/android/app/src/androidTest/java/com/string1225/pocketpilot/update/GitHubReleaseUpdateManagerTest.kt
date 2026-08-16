package com.string1225.pocketpilot.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class GitHubReleaseUpdateManagerTest {
    @Test
    fun checksDownloadsVerifiesAndRequiresUnknownSourcesPermission() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val installed = InstalledAppIdentity(
            packageName = context.packageName,
            versionName = "1.0.0",
            versionCode = 10L,
            signingCertificateSha256 = setOf("certificate"),
        )
        val release = resolvedRelease()
        val source = FakeSource(release)
        val verifier = FakeVerifier(installed)
        val installer = FakeInstaller(canInstall = false)
        val manager = GitHubReleaseUpdateManager.createForTest(
            context = context,
            source = source,
            verifier = verifier,
            installer = installer,
            clock = { 100_000L },
        )

        assertTrue(manager.check(force = true) is UpdateCheckResult.Available)
        assertEquals(UpdatePhase.AVAILABLE, manager.state.value.phase)
        assertTrue(manager.download() is DownloadResult.Ready)
        assertEquals(UpdatePhase.READY_TO_INSTALL, manager.state.value.phase)
        assertEquals(4L, manager.state.value.bytesDownloaded)
        assertTrue(manager.download() is DownloadResult.Ready)
        assertEquals(1, source.downloadCalls)
        assertEquals(InstallLaunchResult.PermissionRequired, manager.install())
        assertEquals(2, verifier.verifyCalls)
        assertEquals(UpdatePhase.INSTALL_PERMISSION_REQUIRED, manager.state.value.phase)
        assertTrue(manager.openUnknownSourcesSettings())
        assertTrue(installer.openedSettings)
    }

    @Test
    fun installFailsClosedWhenTheVerifiedCacheFileChanges() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val installed = InstalledAppIdentity(
            packageName = context.packageName,
            versionName = "1.0.0",
            versionCode = 10L,
            signingCertificateSha256 = setOf("certificate"),
        )
        val source = FakeSource(resolvedRelease())
        val installer = FakeInstaller(canInstall = true)
        val manager = GitHubReleaseUpdateManager.createForTest(
            context = context,
            source = source,
            verifier = FakeVerifier(installed),
            installer = installer,
            clock = { 100_000L },
        )
        manager.check(force = true)
        assertTrue(manager.download() is DownloadResult.Ready)

        check(source.destination.setWritable(true, true))
        source.destination.writeBytes(byteArrayOf(9, 9, 9, 9))
        assertTrue(manager.install() is InstallLaunchResult.Failed)
        assertEquals(UpdatePhase.ERROR, manager.state.value.phase)
        assertTrue(!installer.launched)
    }

    @Test
    fun automaticChecksAreThrottledWithoutCallingGitHubAgain() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("pocketpilot.update", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try {
            val installed = InstalledAppIdentity(
                packageName = context.packageName,
                versionName = "1.0.0",
                versionCode = 10L,
                signingCertificateSha256 = setOf("certificate"),
            )
            val source = FakeSource(resolvedRelease())
            val manager = GitHubReleaseUpdateManager.createForTest(
                context = context,
                source = source,
                verifier = FakeVerifier(installed),
                installer = FakeInstaller(canInstall = false),
                clock = { 100_000L },
            )

            assertTrue(manager.check(force = false) is UpdateCheckResult.Available)
            assertTrue(manager.check(force = false) is UpdateCheckResult.Throttled)
            assertEquals(1, source.latestCalls)
            assertEquals(UpdatePhase.THROTTLED, manager.state.value.phase)
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test
    fun installedPackageInspectorReturnsTheRealSigner() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val installed = AndroidUpdateApkVerifier(context).installedIdentity()
        assertEquals(context.packageName, installed.packageName)
        assertTrue(installed.versionCode > 0L)
        assertTrue(installed.signingCertificateSha256.isNotEmpty())
    }

    @Test
    fun installerIntentUsesReadOnlyContentUriGrant() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = UpdateCache(context)
        val launcher = AndroidUpdateInstallLauncher(context, cache)
        val uri = Uri.parse("content://${context.packageName}.fileprovider/updates/pocketpilot-1.2.3.apk")
        val intent = launcher.createInstallIntent(uri)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(AndroidUpdateInstallLauncher.APK_MIME_TYPE, intent.type)
        assertEquals(uri, intent.data)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
    }

    @Test
    fun fileProviderExposesOnlyThePrivateUpdateCacheEntry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = UpdateCache(context)
        val apk = cache.prepare("9.8.7")
        apk.writeBytes(byteArrayOf(1, 2, 3))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        assertEquals("content", uri.scheme)
        assertTrue(uri.path.orEmpty().contains("updates"))
        assertEquals(apk.canonicalFile, cache.requireOwnedApk(apk))

        val unrelated = File(context.cacheDir, "unrelated.apk").apply { writeBytes(byteArrayOf(1)) }
        try {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                cache.requireOwnedApk(unrelated)
            }
        } finally {
            unrelated.delete()
        }
    }

    private fun resolvedRelease(): ResolvedUpdateRelease {
        val version = "1.2.3"
        val tag = "v$version"
        val apkName = GitHubReleasePolicy.apkAssetName(version)
        val checksumName = GitHubReleasePolicy.checksumAssetName(version)
        return ResolvedUpdateRelease(
            public = UpdateRelease(tag, version, tag, "", "2026-08-16T12:00:00Z", 4L),
            apkAsset = UpdateAsset(
                apkName,
                4L,
                "https://github.com/string1225/PocketPilot/releases/download/$tag/$apkName",
            ),
            checksumAsset = UpdateAsset(
                checksumName,
                89L,
                "https://github.com/string1225/PocketPilot/releases/download/$tag/$checksumName",
            ),
        )
    }

    private class FakeSource(private val release: ResolvedUpdateRelease) : GitHubReleaseSource {
        var latestCalls = 0
        var downloadCalls = 0
        lateinit var destination: File

        override suspend fun latestRelease(): ResolvedUpdateRelease {
            latestCalls += 1
            return release
        }

        override suspend fun download(
            release: ResolvedUpdateRelease,
            destination: File,
            onProgress: (Long, Long?) -> Unit,
        ): DownloadedUpdate {
            downloadCalls += 1
            this.destination = destination
            destination.writeBytes(byteArrayOf(1, 2, 3, 4))
            onProgress(4L, 4L)
            return DownloadedUpdate(destination, "a".repeat(64), 4L)
        }
    }

    private class FakeVerifier(private val installed: InstalledAppIdentity) : UpdateApkVerifier {
        var verifyCalls = 0

        override fun installedIdentity(): InstalledAppIdentity = installed

        override fun verify(
            file: File,
            expectedSha256: String,
            expectedSizeBytes: Long,
            expectedVersionName: String,
        ): VerifiedUpdateApk {
            verifyCalls += 1
            require(expectedVersionName == "1.2.3")
            require(file.readBytes().contentEquals(byteArrayOf(1, 2, 3, 4))) { "APK contents changed" }
            return VerifiedUpdateApk(file, expectedSha256, expectedSizeBytes, installed.versionCode + 1L)
        }
    }

    private class FakeInstaller(private val canInstall: Boolean) : UpdateInstallLauncher {
        var openedSettings = false
        var launched = false

        override fun canRequestPackageInstalls(): Boolean = canInstall

        override fun launchInstaller(apkFile: File): Boolean {
            launched = true
            return true
        }

        override fun openUnknownSourcesSettings(): Boolean {
            openedSettings = true
            return true
        }
    }
}
