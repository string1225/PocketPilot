package com.string1225.agentdock.ui

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
import com.string1225.agentdock.model.Checkpoint
import com.string1225.agentdock.model.CheckpointSource

@Composable
fun CheckpointsScreen(
    checkpoints: List<Checkpoint>,
    onRestore: (String) -> Unit,
) {
    var restoreTarget by remember { mutableStateOf<Checkpoint?>(null) }

    Column(Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("Checkpoints", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "高频保存 Workspace，不会改写 Git HEAD",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (checkpoints.isEmpty()) {
            EmptyState(
                icon = Icons.Default.History,
                title = "还没有 Checkpoint",
                body = "创建或保存文件后，安全历史会出现在这里。",
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
                    CheckpointCard(checkpoint = checkpoint, onRestore = { restoreTarget = checkpoint })
                }
            }
        }
    }

    restoreTarget?.let { checkpoint ->
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text("恢复此 Checkpoint？") },
            text = {
                Text(
                    "当前 Workspace 会被替换为 ${formatTimestamp(checkpoint.createdAt)} 的状态，并在完成后记录新的 Checkpoint；Git HEAD 不会改变。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        restoreTarget = null
                        onRestore(checkpoint.id)
                    },
                ) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { restoreTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun CheckpointCard(
    checkpoint: Checkpoint,
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
                AssistChip(onClick = {}, label = { Text(checkpoint.source.label()) })
                Column(modifier = Modifier.weight(1f)) {
                    Text(checkpoint.safeDescription(), fontWeight = FontWeight.SemiBold)
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
                    "共 ${checkpoint.totalFiles} 个文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onRestore, modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Default.Restore, contentDescription = null)
                Text("恢复", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun DiffLabel(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.Bold)
}

private fun CheckpointSource.label(): String = when (this) {
    CheckpointSource.AGENT -> "Agent"
    CheckpointSource.USER -> "用户"
    CheckpointSource.GIT -> "Git"
    CheckpointSource.IMPORT -> "导入"
}

private fun Checkpoint.safeDescription(): String {
    if (parentId == null) return "项目初始状态"
    val looksCorrupted = description.any { it in "鍒淇鎭椤绉诲姩" }
    if (!looksCorrupted && description.isNotBlank()) return description
    return when (source) {
        CheckpointSource.AGENT -> "Agent 修改工作区"
        CheckpointSource.USER -> "用户修改工作区"
        CheckpointSource.GIT -> "Git 操作后的工作区"
        CheckpointSource.IMPORT -> "导入文件"
    }
}
