package com.string1225.pocketpilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
fun OfflineDemoBanner(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    runtimeAvailable: Boolean = true,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.CloudOff, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (runtimeAvailable) "离线演示模式" else "Agent Runtime 不可用",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!compact) {
                    Text(
                        if (runtimeAvailable) {
                            "项目、文件和检查点会真实保存；Agent 使用本地指令 Provider，不发送网络请求。"
                        } else {
                            "Project、Files 和 Checkpoints 仍可使用；Agent 当前运行在受限降级模式。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

@Composable
fun formatTimestamp(epochMillis: Long): String = remember(epochMillis) {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMillis))
}

fun formatFileSize(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "%.1f KiB".format(bytes / 1_024.0)
    else -> "%.1f MiB".format(bytes / 1_048_576.0)
}
