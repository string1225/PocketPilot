package com.string1225.pocketpilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.string1225.pocketpilot.model.Checkpoint
import com.string1225.pocketpilot.model.CheckpointSource
import com.string1225.pocketpilot.model.AppLanguage

@Composable
fun CheckpointsScreen(
    checkpoints: List<Checkpoint>,
    language: AppLanguage,
    onRestore: (String) -> Unit,
) {
    var restoreTarget by remember { mutableStateOf<Checkpoint?>(null) }

    Column(Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                ppText(language, "检查点", "Checkpoints"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                ppText(
                    language,
                    "高频保存 Workspace，不会改写 Git HEAD",
                    "Frequent Workspace snapshots that never rewrite Git HEAD",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (checkpoints.isEmpty()) {
            EmptyState(
                icon = Icons.Default.History,
                title = ppText(language, "还没有 Checkpoint", "No Checkpoints yet"),
                body = ppText(
                    language,
                    "创建或保存文件后，安全历史会出现在这里。",
                    "Your safety history appears here after creating or saving a file.",
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(checkpoints, key = { it.id }) { checkpoint ->
                    CheckpointCard(
                        checkpoint = checkpoint,
                        language = language,
                        onRestore = { restoreTarget = checkpoint },
                    )
                }
            }
        }
    }

    restoreTarget?.let { checkpoint ->
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text(ppText(language, "恢复此 Checkpoint？", "Restore this Checkpoint?")) },
            text = {
                Text(
                    ppText(
                        language,
                        "当前 Workspace 会被替换为 ${formatTimestamp(checkpoint.createdAt)} 的状态，并在完成后记录新的 Checkpoint；Git HEAD 不会改变。",
                        "The current Workspace will be replaced with its state at ${formatTimestamp(checkpoint.createdAt)}. A new Checkpoint will be recorded and Git HEAD will not change.",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        restoreTarget = null
                        onRestore(checkpoint.id)
                    },
                ) { Text(ppText(language, "恢复", "Restore")) }
            },
            dismissButton = {
                TextButton(onClick = { restoreTarget = null }) { Text(ppText(language, "取消", "Cancel")) }
            },
        )
    }
}

@Composable
private fun CheckpointCard(
    checkpoint: Checkpoint,
    language: AppLanguage,
    onRestore: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(onClick = {}, label = { Text(checkpoint.source.label(language)) })
                Column(modifier = Modifier.weight(1f)) {
                    Text(checkpoint.safeDescription(language), fontWeight = FontWeight.SemiBold)
                    Text(
                        formatTimestamp(checkpoint.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DiffLabel("+${checkpoint.addedFiles}", MaterialTheme.colorScheme.primary)
                DiffLabel("~${checkpoint.modifiedFiles}", MaterialTheme.colorScheme.tertiary)
                DiffLabel("−${checkpoint.deletedFiles}", MaterialTheme.colorScheme.error)
                Text(
                    ppText(language, "共 ${checkpoint.totalFiles} 个文件", "${checkpoint.totalFiles} files total"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onRestore, modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Default.Restore, contentDescription = null)
                Text(ppText(language, "恢复", "Restore"), modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun DiffLabel(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.Bold)
}

private fun CheckpointSource.label(language: AppLanguage): String = when (this) {
    CheckpointSource.AGENT -> "Agent"
    CheckpointSource.USER -> ppText(language, "用户", "User")
    CheckpointSource.GIT -> "Git"
    CheckpointSource.IMPORT -> ppText(language, "导入", "Import")
}

private fun Checkpoint.safeDescription(language: AppLanguage): String {
    if (parentId == null) return ppText(language, "项目初始状态", "Initial project state")
    val looksCorrupted = description.any { it in "鍒淇鎭椤绉诲姩" }
    if (!looksCorrupted && description.isNotBlank()) return description
    return when (source) {
        CheckpointSource.AGENT -> ppText(language, "Agent 修改工作区", "Agent changed the Workspace")
        CheckpointSource.USER -> ppText(language, "用户修改工作区", "User changed the Workspace")
        CheckpointSource.GIT -> ppText(language, "Git 操作后的工作区", "Workspace after Git operation")
        CheckpointSource.IMPORT -> ppText(language, "导入文件", "Imported files")
    }
}
