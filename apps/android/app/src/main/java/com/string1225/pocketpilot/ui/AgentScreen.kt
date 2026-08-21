package com.string1225.pocketpilot.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.string1225.pocketpilot.model.AgentRunStatus
import com.string1225.pocketpilot.model.AppLanguage
import com.string1225.pocketpilot.model.ChatImageAttachment
import com.string1225.pocketpilot.model.TimelineItem
import com.string1225.pocketpilot.model.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AgentScreen(
    timeline: List<TimelineItem>,
    input: String,
    attachments: List<ChatImageAttachment>,
    queuedCount: Int,
    status: AgentRunStatus,
    offlineDemo: Boolean,
    runtimeAvailable: Boolean,
    language: AppLanguage,
    speechInputController: SpeechInputController,
    onInputChange: (String) -> Unit,
    onImportImages: (List<Uri>) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val speechState by speechInputController.state.collectAsStateWithLifecycle()
    var speechFailure by remember { mutableStateOf<SpeechInputFailure?>(null) }
    val active = status == AgentRunStatus.RUNNING || status == AgentRunStatus.WAITING_FOR_APPROVAL
    val messages = remember(timeline, status) { presentChatTimeline(timeline, status) }
    val hasDraft = input.isNotBlank() || attachments.isNotEmpty()
    val primaryActionStops = active && !hasDraft

    fun startSpeechInput() {
        focusManager.clearFocus(force = true)
        speechFailure = null
        speechInputController.start(
            language = language,
            initialText = input,
            onText = onInputChange,
            onFailure = { speechFailure = it },
        )
    }

    val recordAudioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startSpeechInput() else speechFailure = SpeechInputFailure.PERMISSION_DENIED
    }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) onImportImages(uris)
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.body?.length, active, queuedCount) {
        val extraRunningRow = if (active) 1 else 0
        val totalRows = messages.size + extraRunningRow
        if (totalRows > 0) listState.animateScrollToItem(totalRows - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (messages.isEmpty() && !active) {
            EmptyConversation(
                offlineDemo = offlineDemo,
                runtimeAvailable = runtimeAvailable,
                language = language,
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
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    ChatMessage(message, language)
                }
                if (active) {
                    item(key = "active-run-indicator") {
                        RunningIndicator(language)
                    }
                }
            }
        }

        if (attachments.isNotEmpty()) {
            AttachmentComposerStrip(
                attachments = attachments,
                onRemove = onRemoveAttachment,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (queuedCount > 0) {
            Text(
                ppText(language, "已排队 $queuedCount 条消息", "$queuedCount message(s) queued"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
            )
        }

        speechFailure?.let { failure ->
            Text(
                failure.label(language),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            CompactComposerField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth(),
                onFocusChange = { isFocused ->
                    if (isFocused && speechState.isActive) speechInputController.stop()
                },
                placeholder = {
                    Text(
                        if (offlineDemo) {
                            ppText(language, "例如：/create notes/a.md | hello", "Example: /create notes/a.md | hello")
                        } else {
                            ppText(language, "描述任务，也可以附上图片…", "Describe a task or attach images…")
                        },
                    )
                },
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (hasDraft) {
                        if (speechState.isActive) speechInputController.stop()
                        onSend()
                    }
                }),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                IconButton(
                    onClick = {
                        if (speechState.isActive) speechInputController.stop()
                        imagePicker.launch(arrayOf("image/*"))
                    },
                    enabled = attachments.size < MAX_CHAT_IMAGES,
                    modifier = Modifier.size(COMPOSER_BUTTON_SIZE),
                ) {
                    Icon(
                        Icons.Rounded.AddPhotoAlternate,
                        contentDescription = ppText(language, "添加图片", "Add images"),
                        modifier = Modifier.size(COMPOSER_ICON_SIZE),
                    )
                }
                IconButton(
                    onClick = {
                        if (speechState.isActive) {
                            speechInputController.stop()
                        } else if (
                            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            startSpeechInput()
                        } else {
                            recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.size(COMPOSER_BUTTON_SIZE),
                    colors = if (speechState.isActive) {
                        IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else {
                        IconButtonDefaults.iconButtonColors()
                    },
                ) {
                    Icon(
                        if (speechState.isActive) Icons.Rounded.GraphicEq else Icons.Rounded.KeyboardVoice,
                        contentDescription = if (speechState.isActive) {
                            ppText(language, "停止语音输入", "Stop voice input")
                        } else {
                            ppText(language, "语音输入", "Voice input")
                        },
                        modifier = Modifier.size(COMPOSER_ICON_SIZE),
                    )
                }
                FilledIconButton(
                    onClick = {
                        if (speechState.isActive) speechInputController.stop()
                        if (primaryActionStops) onCancel() else onSend()
                    },
                    enabled = primaryActionStops || hasDraft,
                    modifier = Modifier.size(COMPOSER_BUTTON_SIZE),
                    colors = if (primaryActionStops) {
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        )
                    } else {
                        IconButtonDefaults.filledIconButtonColors()
                    },
                ) {
                    Icon(
                        if (primaryActionStops) Icons.Rounded.StopCircle else Icons.AutoMirrored.Rounded.Send,
                        contentDescription = if (primaryActionStops) {
                            ppText(language, "终止", "Stop")
                        } else if (active) {
                            ppText(language, "加入队列", "Queue message")
                        } else {
                            ppText(language, "发送", "Send")
                        },
                        modifier = Modifier.size(COMPOSER_ICON_SIZE),
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactComposerField(
    value: String,
    onValueChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    placeholder: @Composable () -> Unit,
    maxLines: Int,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (focused) 2.dp else 1.dp,
            color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = COMPOSER_MIN_HEIGHT)
                .onFocusChanged { focusState ->
                    focused = focusState.isFocused
                    onFocusChange(focusState.isFocused)
                }
                .padding(
                    start = 16.dp,
                    top = 16.dp,
                    end = COMPOSER_ACTIONS_WIDTH,
                    bottom = 16.dp,
                ),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            maxLines = maxLines,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        CompositionLocalProvider(
                            LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                        ) {
                            Box(contentAlignment = Alignment.CenterStart) { placeholder() }
                        }
                    }
                    innerTextField()
                }
            },
        )
    }
}

@Composable
private fun EmptyConversation(
    offlineDemo: Boolean,
    runtimeAvailable: Boolean,
    language: AppLanguage,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                ppText(language, "有什么可以帮你？", "How can I help?"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    !runtimeAvailable -> ppText(
                        language,
                        "运行环境暂不可用；项目和文件仍可正常管理。",
                        "The runtime is unavailable; projects and files remain accessible.",
                    )
                    offlineDemo -> ppText(
                        language,
                        "当前为本地模式，可以直接操作工作区。",
                        "Local mode is active and can operate on the workspace.",
                    )
                    else -> ppText(
                        language,
                        "描述目标，或上传图片作为任务上下文。",
                        "Describe a goal or upload images as task context.",
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChatMessage(
    message: ChatMessagePresentation,
    language: AppLanguage,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = if (message.fromUser) 520.dp else 680.dp),
            shape = RoundedCornerShape(18.dp),
            color = when {
                message.isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.32f)
                message.fromUser -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
                else -> MaterialTheme.colorScheme.surface.copy(alpha = 0f)
            },
        ) {
            Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                if (message.attachments.isNotEmpty()) {
                    ReadOnlyAttachmentStrip(message.attachments)
                    if (message.body.isNotBlank()) Spacer(Modifier.height(8.dp))
                }
                if (message.body.isNotBlank()) {
                    Text(
                        message.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (message.isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                MessageFooter(
                    createdAt = message.createdAt,
                    status = message.status,
                    tokenUsage = message.tokenUsage,
                    language = language,
                )
            }
        }
    }
}

@Composable
private fun MessageFooter(
    createdAt: Long,
    status: ChatMessageStatus,
    tokenUsage: TokenUsage?,
    language: AppLanguage,
) {
    val usage = tokenUsage?.formatted()
    val cacheRate = tokenUsage?.cacheRateFormatted(language)
    Row(
        modifier = Modifier.padding(top = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            formatTimestamp(createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(
            status.label(language),
            style = MaterialTheme.typography.labelSmall,
            color = if (status == ChatMessageStatus.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (usage != null) {
            Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Text(
                usage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (cacheRate != null) {
            Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Text(
                cacheRate,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RunningIndicator(language: AppLanguage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 13.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
        Text(
            ppText(language, "运行中", "Running"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AttachmentComposerStrip(
    attachments: List<ChatImageAttachment>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        items(attachments, key = { it.id }) { attachment ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AttachmentThumbnail(attachment, Modifier.size(52.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.widthIn(max = 130.dp)) {
                        Text(
                            attachment.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            formatFileSize(attachment.sizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onRemove(attachment.id) }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Remove", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadOnlyAttachmentStrip(attachments: List<ChatImageAttachment>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        items(attachments, key = { it.id }) { attachment ->
            Column(modifier = Modifier.width(92.dp)) {
                AttachmentThumbnail(attachment, Modifier.size(92.dp))
                Text(
                    attachment.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun AttachmentThumbnail(
    attachment: ChatImageAttachment,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, attachment.previewUri) {
        value = attachment.previewUri?.let { rawUri ->
            withContext(Dispatchers.IO) {
                decodeAttachmentThumbnail(context, rawUri.toUri())
            }
        }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = attachment.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                Icons.Rounded.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun ChatMessageStatus.label(language: AppLanguage): String = when (this) {
    ChatMessageStatus.QUEUED -> ppText(language, "已排队", "Queued")
    ChatMessageStatus.SENT -> ppText(language, "已发送", "Sent")
    ChatMessageStatus.RUNNING -> ppText(language, "生成中", "Streaming")
    ChatMessageStatus.COMPLETED -> ppText(language, "已完成", "Completed")
    ChatMessageStatus.FAILED -> ppText(language, "失败", "Failed")
    ChatMessageStatus.CANCELLED -> ppText(language, "已终止", "Stopped")
}

private fun SpeechInputFailure.label(language: AppLanguage): String = when (this) {
    SpeechInputFailure.PERMISSION_DENIED -> ppText(language, "需要麦克风权限才能使用语音输入", "Microphone permission is required")
    SpeechInputFailure.UNAVAILABLE -> ppText(language, "设备上没有可用的语音识别服务", "Speech recognition is unavailable")
    SpeechInputFailure.NO_MATCH -> ppText(language, "没有识别到语音，请重试", "No speech was recognized")
    SpeechInputFailure.NETWORK -> ppText(language, "语音识别网络错误", "Speech recognition network error")
    SpeechInputFailure.BUSY -> ppText(language, "语音识别服务正忙，请稍后重试", "Speech recognition is busy")
    SpeechInputFailure.UNKNOWN -> ppText(language, "语音识别失败，请重试", "Speech recognition failed")
}

private fun TokenUsage.formatted(): String? {
    val parts = buildList {
        promptTokens?.let { add("↑$it") }
        completionTokens?.let { add("↓$it") }
    }
    if (parts.isNotEmpty()) return "${parts.joinToString(" ")} tokens"
    return totalTokens?.let { "$it tokens" }
}

private fun TokenUsage.cacheRateFormatted(language: AppLanguage): String? {
    val prompt = promptTokens?.takeIf { it > 0 } ?: return null
    val cached = cachedPromptTokens ?: return null
    val percent = (cached.toDouble() * 100.0 / prompt.toDouble()).coerceIn(0.0, 100.0)
    return ppText(language, "缓存 ${"%.1f".format(percent)}%", "Cache ${"%.1f".format(percent)}%")
}

private fun decodeAttachmentThumbnail(context: Context, uri: Uri): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / sample > THUMBNAIL_SIZE_PX || bounds.outHeight / sample > THUMBNAIL_SIZE_PX) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)?.asImageBitmap()
    }
}.getOrNull()

private const val MAX_CHAT_IMAGES = 6
private const val THUMBNAIL_SIZE_PX = 384
private val COMPOSER_ACTIONS_WIDTH = 130.dp
private val COMPOSER_BUTTON_SIZE = 40.dp
private val COMPOSER_ICON_SIZE = 18.dp
private val COMPOSER_MIN_HEIGHT = 58.dp
