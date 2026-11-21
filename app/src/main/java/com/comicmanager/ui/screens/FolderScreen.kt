package com.comicmanager.ui.screens

import android.icu.text.AlphabeticIndex
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.comicmanager.R
import com.comicmanager.model.Comic
import com.comicmanager.model.ComicFolderSummary
import com.comicmanager.ui.theme.pressScale
import com.comicmanager.ui.viewmodel.FolderActionState
import com.comicmanager.ui.viewmodel.FolderViewModel
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
        containerColor = MaterialTheme.colorScheme.background,
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
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back))
                        }
                    }
                },
                actions = {
                    if (openFolder == null) {
                        val interaction = remember { MutableInteractionSource() }
                        FilledTonalIconButton(
                            onClick = { openDialog(FolderDialog.Create) },
                            modifier = Modifier.padding(end = 8.dp).pressScale(interaction),
                            interactionSource = interaction
                        ) {
                            Icon(Icons.Rounded.Add, stringResource(R.string.folder_v22_new))
                        }
                    } else {
                        Box {
                            IconButton(onClick = { showOptions = true }) {
                                Icon(Icons.Rounded.MoreVert, stringResource(R.string.folder_v22_actions))
                            }
                            DropdownMenu(expanded = showOptions, onDismissRequest = { showOptions = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.folder_v22_rename)) },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                    onClick = {
                                        showOptions = false
                                        openDialog(FolderDialog.Rename(openFolder))
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(R.string.folder_v22_delete), color = MaterialTheme.colorScheme.error)
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
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
                    icon = Icons.Rounded.Delete,
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
                    icon = Icons.Rounded.Close,
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
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        sections.forEach { (label, entries) ->
            stickyHeader(key = "section-$label") {
                Text(
                    label,
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .semantics { heading() }
                        .padding(start = 4.dp, top = 10.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            items(entries, key = { it.id }) { folder ->
                ListItem(
                    headlineContent = { NameText(folder.name) },
                    supportingContent = {
                        Text(pluralStringResource(R.plurals.folder_v22_count, folder.comicCount, folder.comicCount))
                    },
                    leadingContent = { FolderTile() },
                    trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable { onOpen(folder.id) }
                )
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(comics, key = { it.id }) { comic ->
                ListItem(
                    headlineContent = { NameText(comic.name) },
                    leadingContent = { CompactComicCover(comic) },
                    trailingContent = {
                        IconButton(onClick = { onRemove(comic) }) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.folder_v22_remove))
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.clip(MaterialTheme.shapes.large).clickable { onComicClick(comic.id) }
                )
            }
        }
    }
}

@Composable
private fun NameText(name: String) {
    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        FolderTile(size = 72.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            hint,
            modifier = Modifier.widthIn(max = 320.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        if (onCreate != null) {
            Spacer(Modifier.height(16.dp))
            val interaction = remember { MutableInteractionSource() }
            Button(
                onClick = onCreate,
                modifier = Modifier.pressScale(interaction),
                interactionSource = interaction
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.folder_v22_new))
            }
        }
    }
}

@Composable
private fun FolderTile(size: Dp = 44.dp) {
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(27),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_material_folder), null, Modifier.size(size * 0.5f))
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
        icon = {
            if (folder == null) {
                Icon(painterResource(R.drawable.ic_material_folder), contentDescription = null)
            } else {
                Icon(Icons.Rounded.Edit, contentDescription = null)
            }
        },
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
    icon: ImageVector,
    title: String,
    message: String,
    action: FolderActionState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
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
