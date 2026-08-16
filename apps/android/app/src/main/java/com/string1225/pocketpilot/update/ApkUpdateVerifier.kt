package com.string1225.pocketpilot.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal interface UpdateApkVerifier {
    fun installedIdentity(): InstalledAppIdentity

    fun verify(
        file: File,
        expectedSha256: String,
        expectedSizeBytes: Long,
        expectedVersionName: String,
    ): VerifiedUpdateApk
}

internal object ApkUpdatePolicy {
    fun requireSafeUpgrade(
        current: InstalledAppIdentity,
        candidate: InstalledAppIdentity,
        expectedVersionName: String,
    ) {
        require(candidate.packageName == current.packageName) { "Update APK belongs to a different application" }
        require(candidate.versionName == expectedVersionName) { "Update APK version name does not match the release" }
        require(candidate.versionCode > current.versionCode) { "Update APK version is not newer than the installed app" }
        require(current.signingCertificateSha256.isNotEmpty()) { "Installed app signing certificate is unavailable" }
        require(candidate.signingCertificateSha256 == current.signingCertificateSha256) {
            "Update APK signature does not match the installed app"
        }
    }
}

internal class AndroidUpdateApkVerifier(
    context: Context,
) : UpdateApkVerifier {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    override fun installedIdentity(): InstalledAppIdentity =
        packageManager.packageInfo(appContext.packageName)?.toIdentity()
            ?: throw UpdateException("INSTALLED_PACKAGE_UNAVAILABLE", "Installed app information is unavailable")

    override fun verify(
        file: File,
        expectedSha256: String,
        expectedSizeBytes: Long,
        expectedVersionName: String,
    ): VerifiedUpdateApk {
        require(file.isFile && file.length() == expectedSizeBytes) { "Downloaded APK size does not match the release" }
        require(expectedSha256.matches(Regex("^[0-9a-f]{64}$"))) { "Expected APK checksum is invalid" }
        val actualSha256 = sha256(file)
        require(actualSha256 == expectedSha256) { "Downloaded APK checksum does not match the release" }
        val current = installedIdentity()
        val candidate = packageManager.archivePackageInfo(file.absolutePath)?.toIdentity()
            ?: throw IllegalArgumentException("Downloaded file is not a valid Android package")
        ApkUpdatePolicy.requireSafeUpgrade(current, candidate, expectedVersionName)
        return VerifiedUpdateApk(
            file = file,
            sha256 = actualSha256,
            sizeBytes = expectedSizeBytes,
            versionCode = candidate.versionCode,
        )
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.packageInfo(packageName: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            }.getOrNull()
        } else {
            runCatching { getPackageInfo(packageName, packageInfoFlags()) }.getOrNull()
        }

    @Suppress("DEPRECATION")
    private fun PackageManager.archivePackageInfo(path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            getPackageArchiveInfo(path, packageInfoFlags())
        }

    @Suppress("DEPRECATION")
    private fun packageInfoFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.toIdentity(): InstalledAppIdentity {
        val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners?.map { signature -> signature.toByteArray().certificateSha256() }
        } else {
            signatures?.map { signature -> signature.toByteArray().certificateSha256() }
        }.orEmpty().toSet()
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
        return InstalledAppIdentity(
            packageName = packageName,
            versionName = versionName.orEmpty(),
            versionCode = code,
            signingCertificateSha256 = certificates,
        )
    }

    private fun ByteArray.certificateSha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(this)
        return try {
            digest.joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
        } finally {
            fill(0)
            digest.fill(0)
        }
    }

    companion object {
        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(32 * 1024)
            file.inputStream().use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            buffer.fill(0)
            val result = digest.digest()
            return try {
                result.joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
            } finally {
                result.fill(0)
            }
        }
    }
}
