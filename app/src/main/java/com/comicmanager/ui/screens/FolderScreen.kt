package com.comicmanager.ui.screens

import android.icu.text.AlphabeticIndex
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.comicmanager.R
import com.comicmanager.model.Comic
import com.comicmanager.model.ComicFolderSummary
import com.comicmanager.ui.viewmodel.FolderActionState
import com.comicmanager.ui.viewmodel.FolderViewModel
import java.io.File
import java.util.Locale

private sealed interface FolderDialog {
    data object Create : FolderDialog
    data class Rename(val folder: ComicFolderSummary) : FolderDialog
    data class Delete(val folder: ComicFolderSummary) : FolderDialog
    data class Remove(val folderId: Long, val comic: Comic) : FolderDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(
    viewModel: FolderViewModel,
    onComicClick: (Long) -> Unit,
    isActive: Boolean
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val actionState by viewModel.action.collectAsStateWithLifecycle()
    val folderListState = rememberLazyListState()
    var openFolderId by rememberSaveable { mutableStateOf<Long?>(null) }
    var dialog by remember { mutableStateOf<FolderDialog?>(null) }
    var showOptions by remember { mutableStateOf(false) }
    val openFolder = folders?.firstOrNull { it.id == openFolderId }

    fun closeFolder() {
        showOptions = false
        openFolderId = null
    }

    fun openDialog(value: FolderDialog) {
        viewModel.clearActionError()
        dialog = value
    }

    fun dismissDialog() {
        if (actionState.isSaving) return
        dialog = null
        viewModel.clearActionError()
    }

    // A persisted detail route can refer to a folder deleted since the last app run.
    LaunchedEffect(folders, openFolderId) {
        if (folders != null && openFolderId != null && openFolder == null) closeFolder()
    }

    BackHandler(enabled = isActive && openFolderId != null, onBack = ::closeFolder)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        openFolder?.name ?: stringResource(R.string.folder_v22_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (openFolder != null) {
                        IconButton(onClick = ::closeFolder) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                        }
                    }
                },
                actions = {
                    if (openFolder == null) {
                        IconButton(onClick = { openDialog(FolderDialog.Create) }) {
                            Icon(Icons.Default.Add, stringResource(R.string.folder_v22_new))
                        }
                    } else {
                        Box {
                            IconButton(onClick = { showOptions = true }) {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.folder_v22_actions))
                            }
                            DropdownMenu(expanded = showOptions, onDismissRequest = { showOptions = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.folder_v22_rename)) },
                                    onClick = {
                                        showOptions = false
                                        openDialog(FolderDialog.Rename(openFolder))
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(R.string.folder_v22_delete), color = MaterialTheme.colorScheme.error)
                                    },
                                    onClick = {
                                        showOptions = false
                                        openDialog(FolderDialog.Delete(openFolder))
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            val folderList = folders
            if (folderList == null) {
                FolderLoading()
            } else if (openFolder != null) {
                key(openFolder.id) {
                    val comicFlow = remember(viewModel, openFolder.id) { viewModel.comicsInFolder(openFolder.id) }
                    val comics: List<Comic>? by comicFlow.collectAsStateWithLifecycle(initialValue = null)
                    FolderContents(
                        comics = comics,
                        onComicClick = onComicClick,
                        onRemove = { openDialog(FolderDialog.Remove(openFolder.id, it)) }
                    )
                }
            } else if (folderList.isEmpty()) {
                EmptyFolderSpace(
                    title = stringResource(R.string.folder_v22_empty),
                    hint = stringResource(R.string.folder_v22_empty_hint),
                    onCreate = { openDialog(FolderDialog.Create) }
                )
            } else {
                FolderDirectory(
                    folders = folderList,
                    listState = folderListState,
                    onOpen = { openFolderId = it }
                )
            }
        }
    }

    val activeDialog = dialog
    if (activeDialog != null) {
        key(activeDialog) {
            val onSave = { dialog = null }
            when (activeDialog) {
                FolderDialog.Create -> FolderNameDialog(
                    folder = null,
                    action = actionState,
                    onNameChange = viewModel::clearActionError,
                    onDismiss = ::dismissDialog,
                    onSave = { viewModel.createFolder(it, onSave) }
                )
                is FolderDialog.Rename -> FolderNameDialog(
                    folder = activeDialog.folder,
                    action = actionState,
                    onNameChange = viewModel::clearActionError,
                    onDismiss = ::dismissDialog,
                    onSave = { viewModel.renameFolder(activeDialog.folder.id, it, onSave) }
                )
                is FolderDialog.Delete -> FolderConfirmDialog(
                    title = stringResource(R.string.folder_v22_delete),
                    message = stringResource(R.string.folder_v22_delete_prompt, activeDialog.folder.name),
                    action = actionState,
                    onDismiss = ::dismissDialog,
                    onConfirm = {
                        viewModel.deleteFolder(activeDialog.folder.id) {
                            closeFolder()
                            onSave()
                        }
                    }
                )
                is FolderDialog.Remove -> FolderConfirmDialog(
                    title = stringResource(R.string.folder_v22_remove),
                    message = stringResource(R.string.folder_v22_remove_prompt, activeDialog.comic.name),
                    action = actionState,
                    onDismiss = ::dismissDialog,
                    onConfirm = { viewModel.removeComic(activeDialog.folderId, activeDialog.comic.id, onSave) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderDirectory(
    folders: List<ComicFolderSummary>,
    listState: LazyListState,
    onOpen: (Long) -> Unit
) {
    val locale = LocalConfiguration.current.locales[0]
    val sections = remember(folders, locale) { groupFoldersByInitial(folders, locale) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        sections.forEach { (label, entries) ->
            stickyHeader(key = "section-$label") {
                Text(
                    label,
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .semantics { heading() }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(entries, key = { it.id }) { folder ->
                ListItem(
                    headlineContent = { NameText(folder.name) },
                    supportingContent = {
                        Text(pluralStringResource(R.plurals.folder_v22_count, folder.comicCount, folder.comicCount))
                    },
                    leadingContent = { FolderTile() },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier.clickable { onOpen(folder.id) }
                )
                HorizontalDivider(Modifier.padding(start = 72.dp))
            }
        }
    }
}

private fun groupFoldersByInitial(
    folders: List<ComicFolderSummary>,
    locale: Locale
): Map<String, List<ComicFolderSummary>> {
    // Both supported UI languages can contain a mix of Latin names and Chinese names.
    val indexLocale = if (locale.language in setOf("zh", "en")) Locale.SIMPLIFIED_CHINESE else locale
    val index = AlphabeticIndex<ComicFolderSummary>(indexLocale).addLabels(Locale.ENGLISH)
    folders.forEach { index.addRecord(it.name, it) }
    val sections = linkedMapOf<String, MutableList<ComicFolderSummary>>()
    val other = mutableListOf<ComicFolderSummary>()
    for (bucket in index) {
        if (bucket.size() == 0) continue
        val entries = bucket.map { it.data }
        if (bucket.labelType == AlphabeticIndex.Bucket.LabelType.NORMAL) {
            sections.getOrPut(bucket.label) { mutableListOf() }.addAll(entries)
        } else {
            other.addAll(entries)
        }
    }
    if (other.isNotEmpty()) sections["#"] = other
    return sections
}

@Composable
private fun FolderContents(
    comics: List<Comic>?,
    onComicClick: (Long) -> Unit,
    onRemove: (Comic) -> Unit
) {
    when {
        comics == null -> FolderLoading()
        comics.isEmpty() -> EmptyFolderSpace(
            title = stringResource(R.string.folder_v22_empty_comics),
            hint = stringResource(R.string.folder_v22_empty_comics_hint)
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(comics, key = { it.id }) { comic ->
                ListItem(
                    headlineContent = { NameText(comic.name) },
                    leadingContent = {
                        AsyncImage(
                            model = remember(comic.coverPath) { File(comic.coverPath) },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(42.dp, 58.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { onRemove(comic) }) {
                            Icon(Icons.Default.Close, stringResource(R.string.folder_v22_remove))
                        }
                    },
                    modifier = Modifier.clickable { onComicClick(comic.id) }
                )
                HorizontalDivider(Modifier.padding(start = 74.dp))
            }
        }
    }
}

@Composable
private fun NameText(name: String) {
    Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun FolderLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyFolderSpace(title: String, hint: String, onCreate: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        FolderTile()
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        if (onCreate != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onCreate) { Text(stringResource(R.string.folder_v22_new)) }
        }
    }
}

@Composable
private fun FolderTile() {
    val glyphColor = MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(9.dp)).background(glyphColor.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(24.dp)) {
            val folder = Path().apply {
                moveTo(size.width * 0.10f, size.height * 0.27f)
                lineTo(size.width * 0.42f, size.height * 0.27f)
                lineTo(size.width * 0.53f, size.height * 0.39f)
                lineTo(size.width * 0.90f, size.height * 0.39f)
                lineTo(size.width * 0.90f, size.height * 0.82f)
                lineTo(size.width * 0.10f, size.height * 0.82f)
                close()
            }
            drawPath(folder, glyphColor)
        }
    }
}

@Composable
private fun FolderNameDialog(
    folder: ComicFolderSummary?,
    action: FolderActionState,
    onNameChange: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(folder?.name.orEmpty()) }
    val canSave = name.isNotBlank() && !action.isSaving
    fun saveName() {
        if (canSave) onSave(name)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (folder == null) R.string.folder_v22_new else R.string.folder_v22_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; onNameChange() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.folder_v22_name)) },
                singleLine = true,
                enabled = !action.isSaving,
                isError = action.error != null,
                supportingText = if (action.error != null) {
                    { Text(stringResource(action.error)) }
                } else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { saveName() })
            )
        },
        confirmButton = {
            TextButton(onClick = ::saveName, enabled = canSave) {
                Text(stringResource(if (folder == null) R.string.folder_v22_create else R.string.folder_v22_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !action.isSaving) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun FolderConfirmDialog(
    title: String,
    message: String,
    action: FolderActionState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                action.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !action.isSaving) {
                Text(title, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !action.isSaving) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
