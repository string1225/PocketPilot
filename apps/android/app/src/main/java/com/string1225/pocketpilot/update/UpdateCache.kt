package com.string1225.pocketpilot.update

import android.content.Context
import java.io.File
import java.nio.file.Files

internal class UpdateCache(context: Context) {
    private val directory = File(context.applicationContext.cacheDir, DIRECTORY_NAME)

    fun prepare(versionName: String): File {
        val name = GitHubReleasePolicy.apkAssetName(versionName)
        directory.mkdirs()
        check(directory.isDirectory) { "Unable to create update cache directory" }
        require(!Files.isSymbolicLink(directory.toPath())) { "Update cache directory must not be a symbolic link" }
        val canonicalDirectory = directory.canonicalFile
        val destination = File(canonicalDirectory, name).canonicalFile
        require(destination.parentFile == canonicalDirectory) { "Update cache path escaped its directory" }
        directory.listFiles()?.forEach { child ->
            if (child.isFile && child.canonicalFile.parentFile == canonicalDirectory) child.delete()
        }
        return destination
    }

    fun requireOwnedApk(file: File): File {
        val canonicalDirectory = directory.canonicalFile
        val canonical = file.canonicalFile
        require(canonical.parentFile == canonicalDirectory && canonical.name.matches(APK_NAME)) {
            "Update APK is outside private update storage"
        }
        require(canonical.isFile && !Files.isSymbolicLink(canonical.toPath())) { "Update APK is unavailable" }
        return canonical
    }

    companion object {
        private const val DIRECTORY_NAME = "updates"
        private val APK_NAME = Regex("^pocketpilot-(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.apk$")
    }
}
