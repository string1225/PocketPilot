package com.string1225.pocketpilot.update

import java.io.File

data class UpdateRelease(
    val tagName: String,
    val versionName: String,
    val title: String,
    val body: String,
    val publishedAt: String,
    val apkSizeBytes: Long,
)

enum class UpdatePhase {
    IDLE,
    CHECKING,
    THROTTLED,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    READY_TO_INSTALL,
    INSTALL_PERMISSION_REQUIRED,
    INSTALL_LAUNCHED,
    ERROR,
}

data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val currentVersionName: String,
    val currentVersionCode: Long,
    val release: UpdateRelease? = null,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long? = null,
    val errorMessage: String? = null,
    val nextCheckAtMillis: Long? = null,
)

sealed interface UpdateCheckResult {
    data class Available(val release: UpdateRelease) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Throttled(val nextCheckAtMillis: Long) : UpdateCheckResult
    data class Failed(val message: String) : UpdateCheckResult
}

sealed interface DownloadResult {
    data class Ready(val release: UpdateRelease) : DownloadResult
    data class Failed(val message: String) : DownloadResult
}

sealed interface InstallLaunchResult {
    data object Launched : InstallLaunchResult
    data object PermissionRequired : InstallLaunchResult
    data object NotReady : InstallLaunchResult
    data class Failed(val message: String) : InstallLaunchResult
}

internal data class UpdateAsset(
    val name: String,
    val sizeBytes: Long,
    val downloadUrl: String,
)

internal data class ResolvedUpdateRelease(
    val public: UpdateRelease,
    val apkAsset: UpdateAsset,
    val checksumAsset: UpdateAsset,
)

internal data class InstalledAppIdentity(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val signingCertificateSha256: Set<String>,
)

internal data class VerifiedUpdateApk(
    val file: File,
    val sha256: String,
    val sizeBytes: Long,
    val versionCode: Long,
)

internal class UpdateException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
