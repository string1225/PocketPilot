package com.string1225.pocketpilot.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.string1225.pocketpilot.model.AppLanguage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PluginBundleImportButton(
    language: AppLanguage,
    modifier: Modifier = Modifier,
    onBundleLoaded: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { readPluginBundle(context, uri) }
            }.fold(onSuccess = onBundleLoaded, onFailure = { error ->
                onError(error.message ?: "Could not read plugin bundle")
            })
        }
    }
    OutlinedButton(
        modifier = modifier,
        onClick = { launcher.launch(arrayOf("application/json", "text/json", "text/plain")) },
    ) {
        Icon(Icons.Default.UploadFile, contentDescription = null)
        Text(ppText(language, "选择 JSON 文件", "Choose JSON file"))
    }
}

private fun readPluginBundle(context: Context, uri: android.net.Uri): String {
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IllegalArgumentException("The selected plugin bundle cannot be opened.")
    return input.use { readBoundedUtf8(it, MAX_PLUGIN_BUNDLE_BYTES) }
}

internal fun readBoundedUtf8(input: InputStream, maxBytes: Int): String {
    require(maxBytes > 0) { "maxBytes must be positive." }
    val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        require(total <= maxBytes) { "Plugin bundle exceeds the $maxBytes-byte limit." }
        output.write(buffer, 0, read)
    }
    require(total > 0) { "Plugin bundle is empty." }
    return Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(output.toByteArray()))
        .toString()
}

internal const val MAX_PLUGIN_BUNDLE_BYTES = 384 * 1024
