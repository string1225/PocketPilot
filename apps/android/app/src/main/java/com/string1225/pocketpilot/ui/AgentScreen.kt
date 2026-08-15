package com.string1225.pocketpilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.string1225.pocketpilot.model.AgentRunStatus
import com.string1225.pocketpilot.model.AppLanguage
import com.string1225.pocketpilot.model.TimelineItem
import com.string1225.pocketpilot.model.TimelineItemKind

@Composable
fun AgentScreen(
    timeline: List<TimelineItem>,
    input: String,
    status: AgentRunStatus,
    offlineDemo: Boolean,
    runtimeAvailable: Boolean,
    language: AppLanguage,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    val listState = rememberLazyListState()
    val active = status == AgentRunStatus.RUNNING || status == AgentRunStatus.WAITING_FOR_APPROVAL

    LaunchedEffect(timeline.size) {
        if (timeline.isNotEmpty()) listState.animateScrollToItem(timeline.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    ppText(language, "聊天", "Chat"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when {
                        !runtimeAvailable -> ppText(
                            language,
                            "Runtime 不可用 · 仅仓储降级模式",
                            "Runtime unavailable · repository-only fallback",
                        )
                        offlineDemo -> ppText(
                            language,
                            "本地指令 Provider · Workspace 工具会真实执行",
                            "Local provider · Workspace tools execute for real",
                        )
                        else -> ppText(language, "工具调用时间线", "Tool-calling timeline")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AssistChip(onClick = {}, label = { Text(status.label(language)) })
        }

        if (timeline.isEmpty()) {
            EmptyState(
                icon = Icons.Default.SmartToy,
                title = when {
                    !runtimeAvailable -> ppText(language, "Agent Runtime 当前不可用", "Agent Runtime is unavailable")
                    offlineDemo -> ppText(language, "开始一段本地会话", "Start a local chat")
                    else -> ppText(language, "开始一个 Agent Run", "Start an Agent run")
                },
                body = when {
                    !runtimeAvailable -> ppText(
                        language,
                        "本地项目、文件和 Checkpoints 不受影响；更新 Android System WebView 后重启应用再试。",
                        "Local projects, files, and checkpoints still work. Update Android System WebView and restart the app.",
                    )
                    offlineDemo -> ppText(
                        language,
                        "输入 /list，或 /create path | content。消息和 Agent 结果会保存在当前会话中。",
                        "Try /list or /create path | content. Messages and Agent results are saved in this chat.",
                    )
                    else -> ppText(
                        language,
                        "描述目标，Agent 将在当前项目的授权范围内工作。",
                        "Describe a goal and the Agent will work within the selected project's permissions.",
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(timeline, key = { it.id }) { item -> TimelineCard(item) }
            }
        }

        if (active) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Text(ppText(language, "停止 Run", "Stop run"), modifier = Modifier.padding(start = 6.dp))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (offlineDemo) {
                            ppText(language, "例如：/create notes/a.md | hello", "Example: /create notes/a.md | hello")
                        } else {
                            ppText(language, "告诉 Agent 要完成什么…", "Tell the Agent what to do…")
                        },
                    )
                },
                maxLines = 5,
                enabled = !active,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank() && !active) onSend() }),
            )
            FilledIconButton(onClick = onSend, enabled = input.isNotBlank() && !active) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = ppText(language, "发送消息", "Send message"))
            }
        }
    }
}

@Composable
private fun TimelineCard(item: TimelineItem) {
    val fromUser = item.kind == TimelineItemKind.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(modifier = Modifier.widthIn(max = 560.dp)) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = item.kind.icon(),
                    contentDescription = null,
                    tint = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                Column {
                    Text(item.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(item.body, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        formatTimestamp(item.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

private fun AgentRunStatus.label(language: AppLanguage): String = when (this) {
    AgentRunStatus.IDLE -> ppText(language, "待命", "Ready")
    AgentRunStatus.RUNNING -> ppText(language, "运行中", "Running")
    AgentRunStatus.WAITING_FOR_APPROVAL -> ppText(language, "等待确认", "Awaiting approval")
    AgentRunStatus.COMPLETED -> ppText(language, "已完成", "Completed")
    AgentRunStatus.FAILED -> ppText(language, "失败", "Failed")
    AgentRunStatus.CANCELLED -> ppText(language, "已取消", "Cancelled")
}

private fun TimelineItemKind.icon(): ImageVector = when (this) {
    TimelineItemKind.USER -> Icons.Default.Person
    TimelineItemKind.ASSISTANT -> Icons.Default.SmartToy
    TimelineItemKind.TOOL -> Icons.Default.Build
    TimelineItemKind.STATUS -> Icons.Default.Info
    TimelineItemKind.ERROR -> Icons.Default.ErrorOutline
}
