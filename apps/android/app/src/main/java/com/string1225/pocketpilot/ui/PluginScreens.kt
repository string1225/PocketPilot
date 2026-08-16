package com.string1225.pocketpilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.string1225.pocketpilot.model.AppLanguage
import com.string1225.pocketpilot.model.InstalledPlugin
import com.string1225.pocketpilot.model.PluginInstallPreview

@Composable
fun PluginManagerDialog(
    language: AppLanguage,
    plugins: List<InstalledPlugin>,
    onPreviewBundle: (String) -> Result<PluginInstallPreview>,
    onInstallBundle: (String) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var showInstaller by remember { mutableStateOf(false) }
    var pendingInstall by remember { mutableStateOf<PendingPluginInstall?>(null) }
    var pendingEnable by remember { mutableStateOf<InstalledPlugin?>(null) }
    var pendingDelete by remember { mutableStateOf<InstalledPlugin?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ppText(language, "插件", "Plugins")) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    ppText(
                        language,
                        "MVP 插件只能进行纯计算。每次调用都在独立 Worker 中运行，无法访问网络、文件、NativeBridge、模型或凭据。",
                        "MVP plugins are pure computation only. Every call runs in an isolated Worker without network, files, NativeBridge, model, or credential access.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { showInstaller = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(
                        ppText(language, "粘贴 JSON 安装包", "Paste JSON bundle"),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (plugins.isEmpty()) {
                    Text(ppText(language, "尚未安装插件。", "No plugins installed."))
                } else {
                    plugins.forEach { plugin ->
                        PluginRow(
                            language = language,
                            plugin = plugin,
                            onEnabledChange = { enabled ->
                                if (enabled) pendingEnable = plugin else onSetEnabled(plugin.id, false)
                            },
                            onDelete = { pendingDelete = plugin },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(ppText(language, "完成", "Done")) }
        },
    )

    if (showInstaller) {
        PluginInstallDialog(
            language = language,
            onPreview = onPreviewBundle,
            onReviewed = { bundle, preview ->
                showInstaller = false
                pendingInstall = PendingPluginInstall(bundle, preview)
            },
            onDismiss = { showInstaller = false },
        )
    }

    pendingInstall?.let { pending ->
        PluginPermissionDialog(
            language = language,
            title = ppText(language, "确认安装插件", "Confirm plugin installation"),
            plugin = pending.preview.plugin,
            detail = ppText(
                language,
                "安装后默认停用。源码 ${pending.preview.sourceSizeBytes} 字节。\nSHA-256：${pending.preview.plugin.sourceSha256.chunked(8).joinToString(" ")}",
                "The plugin stays disabled after installation. Source: ${pending.preview.sourceSizeBytes} bytes.\nSHA-256: ${pending.preview.plugin.sourceSha256.chunked(8).joinToString(" ")}",
            ),
            confirmLabel = ppText(language, "安装", "Install"),
            onConfirm = {
                pendingInstall = null
                onInstallBundle(pending.bundleJson)
            },
            onDismiss = { pendingInstall = null },
        )
    }

    pendingEnable?.let { plugin ->
        PluginPermissionDialog(
            language = language,
            title = ppText(language, "确认启用插件", "Confirm plugin activation"),
            plugin = plugin,
            detail = ppText(
                language,
                "启用后，模型可调用下面列出的纯计算工具。插件仍无法访问任何原生能力。",
                "Once enabled, the model can call the pure-compute tools below. The plugin still cannot access native capabilities.",
            ),
            confirmLabel = ppText(language, "启用", "Enable"),
            onConfirm = {
                pendingEnable = null
                onSetEnabled(plugin.id, true)
            },
            onDismiss = { pendingEnable = null },
        )
    }

    pendingDelete?.let { plugin ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(ppText(language, "卸载插件？", "Uninstall plugin?")) },
            text = { Text("${plugin.name} (${plugin.id})") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete(plugin.id)
                    },
                ) { Text(ppText(language, "卸载", "Uninstall")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(ppText(language, "取消", "Cancel"))
                }
            },
        )
    }
}

@Composable
private fun PluginRow(
    language: AppLanguage,
    plugin: InstalledPlugin,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = { Text("${plugin.name} · ${plugin.version}") },
        supportingContent = {
            Column {
                Text(plugin.description.ifBlank { plugin.id })
                Text(
                    ppText(
                        language,
                        "${plugin.tools.size} 个纯计算工具 · ${plugin.sourceSha256.take(12)}…",
                        "${plugin.tools.size} pure-compute tool(s) · ${plugin.sourceSha256.take(12)}…",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        trailingContent = {
            Row {
                Switch(checked = plugin.enabled, onCheckedChange = onEnabledChange)
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = ppText(language, "卸载", "Uninstall"),
                    )
                }
            }
        },
    )
}

@Composable
private fun PluginInstallDialog(
    language: AppLanguage,
    onPreview: (String) -> Result<PluginInstallPreview>,
    onReviewed: (String, PluginInstallPreview) -> Unit,
    onDismiss: () -> Unit,
) {
    var bundle by remember { mutableStateOf(PLUGIN_BUNDLE_EXAMPLE) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ppText(language, "安装插件", "Install plugin")) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    ppText(
                        language,
                        "粘贴包含 manifest 和 source 的 JSON。source 必须是 (toolName, input) => JSON 函数。可选 sourceSha256 会被严格校验；未提供时应用会计算并保存。",
                        "Paste JSON containing manifest and source. source must be a (toolName, input) => JSON function. An optional sourceSha256 is verified; otherwise the app computes and stores it.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                PluginBundleImportButton(
                    language = language,
                    onBundleLoaded = { imported ->
                        bundle = imported.take(MAX_BUNDLE_CHARS)
                        onPreview(imported).fold(
                            onSuccess = { preview -> onReviewed(imported, preview) },
                            onFailure = { failure ->
                                error = failure.message ?: "Invalid plugin bundle"
                            },
                        )
                    },
                    onError = { error = it },
                )
                OutlinedTextField(
                    value = bundle,
                    onValueChange = {
                        bundle = it.take(MAX_BUNDLE_CHARS)
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 12,
                    maxLines = 20,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    label = { Text("Plugin JSON bundle") },
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onPreview(bundle).fold(
                        onSuccess = { preview -> onReviewed(bundle, preview) },
                        onFailure = { failure -> error = failure.message ?: "Invalid plugin bundle" },
                    )
                },
            ) { Text(ppText(language, "检查权限", "Review permissions")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(ppText(language, "取消", "Cancel")) }
        },
    )
}

@Composable
private fun PluginPermissionDialog(
    language: AppLanguage,
    title: String,
    plugin: InstalledPlugin,
    detail: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("${plugin.name} ${plugin.version}\n${plugin.id}")
                Text(detail)
                Text(
                    ppText(
                        language,
                        "权限：仅纯计算（无网络、无文件、无 NativeBridge、无凭据）",
                        "Permissions: pure computation only (no network, files, NativeBridge, or credentials)",
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
                plugin.tools.forEach { tool ->
                    Text("• plugin.${plugin.id}.${tool.name}\n  ${tool.description}")
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(ppText(language, "取消", "Cancel")) }
        },
    )
}

private data class PendingPluginInstall(
    val bundleJson: String,
    val preview: PluginInstallPreview,
)

private const val MAX_BUNDLE_CHARS = MAX_PLUGIN_BUNDLE_BYTES

private val PLUGIN_BUNDLE_EXAMPLE = """
{
  "manifest": {
    "manifestVersion": 1,
    "id": "com.example.echo",
    "name": "Echo",
    "version": "1.0.0",
    "description": "Returns text without native access.",
    "tools": [{
      "name": "echo",
      "description": "Return the supplied text.",
      "risk": "read",
      "inputSchema": {
        "type": "object",
        "properties": { "text": { "type": "string", "maxLength": 1000 } },
        "required": ["text"],
        "additionalProperties": false
      }
    }]
  },
  "source": "async (toolName, input) => { if (toolName === 'echo') return { text: input.text }; throw new Error('Unknown tool'); }"
}
""".trimIndent()
