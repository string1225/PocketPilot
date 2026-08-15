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
import com.string1225.pocketpilot.model.WorkspaceEntry

@Composable
fun FilesScreen(
    state: PocketPilotUiState,
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
                    "${files.size} 个文本文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = { showCreateDialog = true }, enabled = mutationsEnabled) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("新建", modifier = Modifier.padding(start = 6.dp))
            }
        }
        HorizontalDivider()

        if (files.isEmpty()) {
            EmptyState(
                icon = Icons.Default.FolderOpen,
                title = "Workspace 还是空的",
                body = "新建一个文本文件，开始在手机上编辑项目。",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                action = {
                    Button(onClick = { showCreateDialog = true }, enabled = mutationsEnabled) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("新建文件", modifier = Modifier.padding(start = 8.dp))
                    }
                },
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(files, key = { it.path }) { file ->
                    FileRow(
                        file = file,
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
            Icon(Icons.Default.DeleteOutline, contentDescription = "删除 ${file.path}")
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 52.dp))
}

@Composable
private fun FileEditor(
    path: String,
    content: String,
    dirty: Boolean,
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回文件列表")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(
                    if (dirty) "有未保存修改" else "已保存",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (dirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { confirmDelete = true }, enabled = mutationsEnabled) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "删除文件")
            }
            IconButton(onClick = onSave, enabled = dirty && mutationsEnabled) {
                Icon(Icons.Default.Save, contentDescription = "保存文件")
            }
        }
        if (dirty) {
            AssistChip(
                onClick = onSave,
                label = { Text("保存后自动创建 Checkpoint") },
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
            label = { Text("内容") },
            readOnly = !mutationsEnabled,
        )
    }

    if (confirmDelete) {
        DeleteFileDialog(
            path = path,
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
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var path by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建文件") },
        text = {
            Column {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("相对路径") },
                    placeholder = { Text("src/hello.ts") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (path.isNotBlank()) onCreate(path.trim())
                    }),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "文件只能创建在当前 Project Workspace 内。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(path.trim()) }, enabled = path.isNotBlank()) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DeleteFileDialog(
    path: String,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除文件？") },
        text = { Text("将删除 $path，并自动创建可恢复的 Checkpoint。") },
        confirmButton = {
            TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
