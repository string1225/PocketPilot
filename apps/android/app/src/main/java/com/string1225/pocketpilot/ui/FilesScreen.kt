package com.string1225.pocketpilot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.string1225.pocketpilot.model.AgentRunStatus
import com.string1225.pocketpilot.model.AppLanguage
import com.string1225.pocketpilot.model.WorkspaceEntry

@Composable
fun FilesScreen(
    state: PocketPilotUiState,
    language: AppLanguage,
    onOpenFile: (String) -> Unit,
    onCloseEditor: () -> Unit,
    onEditorChange: (String) -> Unit,
    onCreateFile: (String) -> Unit,
    onSaveFile: () -> Unit,
    onDeleteFile: (String) -> Unit,
) {
    val mutationsEnabled = state.agentStatus != AgentRunStatus.RUNNING &&
        state.agentStatus != AgentRunStatus.WAITING_FOR_APPROVAL
    if (state.selectedFilePath == null) {
        FileBrowser(
            files = state.files,
            language = language,
            mutationsEnabled = mutationsEnabled,
            onOpenFile = onOpenFile,
            onCreateFile = onCreateFile,
            onDeleteFile = onDeleteFile,
        )
    } else {
        FileEditor(
            path = state.selectedFilePath,
            content = state.editorText,
            dirty = state.editorDirty,
            language = language,
            mutationsEnabled = mutationsEnabled,
            onBack = onCloseEditor,
            onChange = onEditorChange,
            onSave = onSaveFile,
            onDelete = { onDeleteFile(state.selectedFilePath) },
        )
    }
}

@Composable
private fun FileBrowser(
    files: List<WorkspaceEntry>,
    language: AppLanguage,
    mutationsEnabled: Boolean,
    onOpenFile: (String) -> Unit,
    onCreateFile: (String) -> Unit,
    onDeleteFile: (String) -> Unit,
) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Workspace", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    ppText(language, "${files.size} 个文本文件", "${files.size} text files"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = { showCreateDialog = true }, enabled = mutationsEnabled) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(ppText(language, "新建", "New"), modifier = Modifier.padding(start = 6.dp))
            }
        }
        HorizontalDivider()

        if (files.isEmpty()) {
            EmptyState(
                icon = Icons.Default.FolderOpen,
                title = ppText(language, "Workspace 还是空的", "Workspace is empty"),
                body = ppText(
                    language,
                    "新建一个文本文件，开始在手机上编辑项目。",
                    "Create a text file to start editing this project on your phone.",
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                action = {
                    Button(onClick = { showCreateDialog = true }, enabled = mutationsEnabled) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text(ppText(language, "新建文件", "New file"), modifier = Modifier.padding(start = 8.dp))
                    }
                },
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(files, key = { it.path }) { file ->
                    FileRow(
                        file = file,
                        language = language,
                        mutationsEnabled = mutationsEnabled,
                        onOpen = { onOpenFile(file.path) },
                        onDelete = { deleteTarget = file.path },
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        NewFileDialog(
            language = language,
            onDismiss = { showCreateDialog = false },
            onCreate = {
                showCreateDialog = false
                onCreateFile(it)
            },
        )
    }

    deleteTarget?.let { path ->
        DeleteFileDialog(
            path = path,
            language = language,
            onDismiss = { deleteTarget = null },
            onDelete = {
                deleteTarget = null
                onDeleteFile(path)
            },
        )
    }
}

@Composable
private fun FileRow(
    file: WorkspaceEntry,
    language: AppLanguage,
    mutationsEnabled: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(file.path, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatFileSize(file.size)} · ${formatTimestamp(file.modifiedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete, enabled = mutationsEnabled) {
            Icon(
                Icons.Default.DeleteOutline,
                contentDescription = ppText(language, "删除 ${file.path}", "Delete ${file.path}"),
            )
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 52.dp))
}

@Composable
private fun FileEditor(
    path: String,
    content: String,
    dirty: Boolean,
    language: AppLanguage,
    mutationsEnabled: Boolean,
    onBack: () -> Unit,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = ppText(language, "返回文件列表", "Back to files"),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(
                    if (dirty) {
                        ppText(language, "有未保存修改", "Unsaved changes")
                    } else {
                        ppText(language, "已保存", "Saved")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (dirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { confirmDelete = true }, enabled = mutationsEnabled) {
                Icon(Icons.Default.DeleteOutline, contentDescription = ppText(language, "删除文件", "Delete file"))
            }
            IconButton(onClick = onSave, enabled = dirty && mutationsEnabled) {
                Icon(Icons.Default.Save, contentDescription = ppText(language, "保存文件", "Save file"))
            }
        }
        if (dirty) {
            AssistChip(
                onClick = onSave,
                label = {
                    Text(ppText(language, "保存后自动创建 Checkpoint", "Saving creates a Checkpoint"))
                },
                enabled = mutationsEnabled,
            )
            Spacer(Modifier.height(6.dp))
        }
        OutlinedTextField(
            value = content,
            onValueChange = onChange,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 12.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            label = { Text(ppText(language, "内容", "Content")) },
            readOnly = !mutationsEnabled,
        )
    }

    if (confirmDelete) {
        DeleteFileDialog(
            path = path,
            language = language,
            onDismiss = { confirmDelete = false },
            onDelete = {
                confirmDelete = false
                onDelete()
            },
        )
    }
}

@Composable
private fun NewFileDialog(
    language: AppLanguage,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var path by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ppText(language, "新建文件", "New file")) },
        text = {
            Column {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(ppText(language, "相对路径", "Relative path")) },
                    placeholder = { Text("src/hello.ts") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (path.isNotBlank()) onCreate(path.trim())
                    }),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    ppText(
                        language,
                        "文件只能创建在当前 Project Workspace 内。",
                        "Files can only be created inside the current Project Workspace.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(path.trim()) }, enabled = path.isNotBlank()) {
                Text(ppText(language, "创建", "Create"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(ppText(language, "取消", "Cancel")) }
        },
    )
}

@Composable
private fun DeleteFileDialog(
    path: String,
    language: AppLanguage,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ppText(language, "删除文件？", "Delete file?")) },
        text = {
            Text(
                ppText(
                    language,
                    "将删除 $path，并自动创建可恢复的 Checkpoint。",
                    "This deletes $path and creates a restorable Checkpoint.",
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text(ppText(language, "删除", "Delete"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(ppText(language, "取消", "Cancel")) }
        },
    )
}
