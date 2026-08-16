package com.string1225.pocketpilot.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

internal interface UpdateInstallLauncher {
    fun canRequestPackageInstalls(): Boolean

    fun launchInstaller(apkFile: File): Boolean

    fun openUnknownSourcesSettings(): Boolean
}

internal class AndroidUpdateInstallLauncher(
    context: Context,
    private val cache: UpdateCache,
) : UpdateInstallLauncher {
    private val appContext = context.applicationContext

    override fun canRequestPackageInstalls(): Boolean =
        runCatching { appContext.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    override fun launchInstaller(apkFile: File): Boolean {
        val ownedFile = cache.requireOwnedApk(apkFile)
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            ownedFile,
        )
        val intent = createInstallIntent(uri)
        return runCatching {
            appContext.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    override fun openUnknownSourcesSettings(): Boolean {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${appContext.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            appContext.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    internal fun createInstallIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, APK_MIME_TYPE)
        clipData = ClipData.newRawUri("PocketPilot update", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
