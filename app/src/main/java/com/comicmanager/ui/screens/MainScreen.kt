package com.comicmanager.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import androidx.lifecycle.Lifecycle
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.comicmanager.R
import com.comicmanager.data.ComicRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.comicmanager.model.Comic
import com.comicmanager.ui.theme.pressScale
import com.comicmanager.ui.viewmodel.MainViewModel
import com.comicmanager.ui.viewmodel.ImportPhase
import com.comicmanager.ui.viewmodel.ImportState
import com.comicmanager.ui.viewmodel.SortMode
import com.comicmanager.ui.viewmodel.ShelfFolderAssignmentState
import com.comicmanager.ui.viewmodel.ShelfFolderAssignmentViewModel
import com.comicmanager.ui.viewmodel.ShelfFolderIssue
import java.io.File

private enum class ComicActionDialogMode {
    ACTIONS,
    BIND_JM_ID,
    DELETE_CONFIRMATION
}

private enum class LanguageChoice(@StringRes val labelRes: Int, val languageTags: String) {
    SYSTEM(R.string.language_follow_system, ""),
    SIMPLIFIED_CHINESE(R.string.language_simplified_chinese, "zh-CN"),
    ENGLISH(R.string.language_english, "en")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalMaterialApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    repository: ComicRepository,
    isActive: Boolean,
    onComicClick: (Long) -> Unit,
    onJmDetail: (String) -> Unit = {}
) {
    val folderViewModel: ShelfFolderAssignmentViewModel = composeViewModel(
        key = "shelf_folder_assignment",
        factory = ShelfFolderAssignmentViewModel.Factory(repository)
    )
    val folderAssignment by folderViewModel.state.collectAsState()
    val allComics by viewModel.allComics.collectAsState()
    val displayComics by viewModel.displayComics.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val currentSortMode by viewModel.sortMode.collectAsState()
    val selectMode by viewModel.selectMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val orderedComicIds = remember(displayComics) { displayComics.map { it.id } }
    val gridState = rememberLazyGridState()
    val bulkProgress by viewModel.bulkProgress.collectAsState()
    val shareState by viewModel.shareState.collectAsState()
    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var showActionMenu by remember { mutableStateOf<Comic?>(null) }
    var actionDialogMode by remember { mutableStateOf(ComicActionDialogMode.ACTIONS) }
    var bindText by remember { mutableStateOf("") }
    var showSortMenu by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    var launchedShareId by rememberSaveable { mutableLongStateOf(0L) }
    val shareLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onShareChooserReturned(context, launchedShareId)
    }

    LaunchedEffect(isActive, lifecycleState, shareState.requestId, shareState.pendingIntent) {
        if (!isActive || !lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        shareState.pendingIntent?.let { intent ->
            val requestId = shareState.requestId
            try {
                launchedShareId = requestId
                shareLauncher.launch(intent)
                viewModel.onShareLaunched(requestId)
            } catch (error: Exception) {
                viewModel.onShareLaunchFailed(context, requestId, error)
            }
        }
    }

    LaunchedEffect(shareState.requestId, shareState.message) {
        shareState.message?.let { message ->
            val requestId = shareState.requestId
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
            viewModel.clearShareMessage(requestId, message)
        }
    }

    val duplicateJmIds = remember(allComics) {
        allComics
            .map { it.jmId.trim() }
            .filter { it.toLongOrNull()?.let { value -> value > 0 } == true }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) { viewModel.importComics(context, uris) }
    }
    val canImport = !importState.isImporting && !bulkProgress.isRunning
    val openZipPicker = { zipPickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed")) }

    LaunchedEffect(importState.batchId, importState.importMessage) {
        importState.importMessage?.let { message ->
            val batchId = importState.batchId
            snackbarHostState.showSnackbar(
                message = message,
                actionLabel = context.getString(R.string.action_close),
                duration = SnackbarDuration.Indefinite
            )
            viewModel.clearImportMessage(batchId)
        }
    }

    val isImporting = importState.isImporting
    LaunchedEffect(folderViewModel, isActive, isImporting) {
        if (!isActive && !isImporting) return@LaunchedEffect
        folderViewModel.notices.collectLatest { notice ->
            // Import progress and its result take precedence over an earlier folder notification.
            if (isImporting) return@collectLatest
            val message = when {
                notice.created -> context.getString(R.string.shelf_folder_v22_created_added, notice.folderName)
                notice.addedCount == 0 -> context.getString(R.string.shelf_folder_v22_already_added, notice.folderName)
                else -> context.resources.getQuantityString(
                    R.plurals.shelf_folder_v22_added, notice.addedCount, notice.addedCount, notice.folderName
                )
            }
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        ShelfSearchField(
                            query = searchQuery,
                            onQueryChange = viewModel::updateSearchQuery,
                            onSearch = { focusManager.clearFocus() },
                            isActive = isActive
                        )
                    } else {
                        Text(stringResource(R.string.main_v22_library), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    if (isSearchActive) {
                        IconButton(onClick = {
                            focusManager.clearFocus()
                            viewModel.toggleSearch()
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close_search))
                        }
                    }
                },
                actions = {
                    if (!isSearchActive) {
                        val searchInteraction = remember { MutableInteractionSource() }
                        val importInteraction = remember { MutableInteractionSource() }
                        IconButton(
                            onClick = viewModel::toggleSearch,
                            modifier = Modifier.pressScale(searchInteraction),
                            interactionSource = searchInteraction
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.action_search))
                        }
                        IconButton(
                            onClick = openZipPicker,
                            enabled = canImport,
                            interactionSource = importInteraction
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_material_file_upload),
                                contentDescription = stringResource(R.string.shelf_import_zip),
                                modifier = Modifier.pressScale(importInteraction)
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.shelf_options))
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            SortMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(mode.labelRes)) },
                                    trailingIcon = {
                                        if (mode == currentSortMode) {
                                            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    },
                                    modifier = Modifier.semantics { selected = mode == currentSortMode },
                                    onClick = { viewModel.setSortMode(mode); showSortMenu = false }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.language)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_material_language), contentDescription = null) },
                                onClick = {
                                    showSortMenu = false
                                    showLanguageDialog = true
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(if (selectMode) R.string.cancel_selection else R.string.select_comics)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_material_checklist), contentDescription = null) },
                                onClick = { showSortMenu = false; viewModel.toggleSelectMode() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.batch_import_id_archives)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_material_file_upload), contentDescription = null) },
                                enabled = canImport,
                                onClick = { showSortMenu = false; openZipPicker() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_duplicate_comics)) },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                colors = MenuDefaults.itemColors(
                                    textColor = MaterialTheme.colorScheme.error,
                                    leadingIconColor = MaterialTheme.colorScheme.error
                                ),
                                enabled = canImport,
                                onClick = {
                                    showSortMenu = false
                                    val count = viewModel.deleteDuplicates()
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            message = context.getString(R.string.duplicates_deleted, count)
                                        )
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_all)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_material_download), contentDescription = null) },
                                enabled = canImport,
                                onClick = {
                                    showSortMenu = false
                                    viewModel.exportAll(context) { msg -> scope.launch { snackbarHostState.showSnackbar(message = msg) } }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.batch_rename_from_zip)) },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                enabled = canImport,
                                onClick = {
                                    showSortMenu = false
                                    viewModel.renameNumericComics(context) { msg -> scope.launch { snackbarHostState.showSnackbar(message = msg) } }
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    actionIconContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { paddingValues ->
        if (isImporting) {
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                ImportProgressContent(importState)
            }
        } else if (displayComics.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                ShelfEmptyState(isSearchActive, canImport, openZipPicker)
            }
        } else {
            var isRefreshing by remember { mutableStateOf(false) }
            val pullRefreshState = rememberPullRefreshState(isRefreshing, { scope.launch { isRefreshing = true; delay(500); isRefreshing = false } })

            Column(Modifier.fillMaxSize().padding(paddingValues)) {
                if (selectMode) {
                    Text(
                        stringResource(R.string.shelf_v2_drag_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                Box(Modifier.weight(1f).then(if (selectMode) Modifier else Modifier.pullRefresh(pullRefreshState))) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = gridState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)
                            .shelfDragSelection(
                                enabled = selectMode,
                                orderedIds = orderedComicIds,
                                selectedIds = selectedIds,
                                gridState = gridState,
                                onSelectionChange = viewModel::replaceSelection
                            ),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(displayComics, key = { it.id }) { comic ->
                            ComicGridItem(
                                comic = comic,
                                isSelected = comic.id in selectedIds,
                                selectMode = selectMode,
                                onClick = { if (selectMode) viewModel.toggleSelection(comic.id) else onComicClick(comic.id) },
                                onLongClick = {
                                    showActionMenu = comic
                                    actionDialogMode = ComicActionDialogMode.ACTIONS
                                    bindText = comic.jmId.trim()
                                }
                            )
                        }
                    }
                    PullRefreshIndicator(
                        isRefreshing,
                        pullRefreshState,
                        Modifier.align(Alignment.TopCenter),
                        backgroundColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                }
                if (selectMode) {
                    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { viewModel.toggleSelectMode() }) { Text(stringResource(R.string.action_cancel)) }
                                Text(
                                    pluralStringResource(R.plurals.selected_count, selectedIds.size, selectedIds.size),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.Center
                                )
                                TextButton(onClick = {
                                    if (selectedIds.size == displayComics.size) viewModel.clearSelection() else viewModel.selectAll()
                                }) { Text(stringResource(if (selectedIds.size == displayComics.size) R.string.invert_selection else R.string.select_all)) }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                val canAct = !bulkProgress.isRunning && selectedIds.isNotEmpty() && !folderAssignment.isSaving
                                ShelfSelectionAction(
                                    label = stringResource(R.string.shelf_folder_v22_add),
                                    icon = painterResource(R.drawable.ic_material_folder),
                                    enabled = canAct,
                                    onClick = {
                                        val liveSelected = allComics.filter { it.id in selectedIds }.map { it.id }
                                        folderViewModel.open(liveSelected)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                ShelfSelectionAction(
                                    label = stringResource(R.string.action_export),
                                    icon = painterResource(R.drawable.ic_material_download),
                                    enabled = canAct,
                                    onClick = { viewModel.exportSelected(context) { msg -> scope.launch { snackbarHostState.showSnackbar(message = msg) } } },
                                    modifier = Modifier.weight(1f)
                                )
                                ShelfSelectionAction(
                                    label = stringResource(R.string.action_share),
                                    icon = rememberVectorPainter(Icons.Filled.Share),
                                    enabled = canAct,
                                    onClick = { viewModel.shareSelected(context) },
                                    modifier = Modifier.weight(1f)
                                )
                                ShelfSelectionAction(
                                    label = stringResource(R.string.action_delete),
                                    icon = rememberVectorPainter(Icons.Filled.Delete),
                                    enabled = canAct,
                                    onClick = { viewModel.deleteSelected(context) { msg -> scope.launch { snackbarHostState.showSnackbar(message = msg) } } },
                                    modifier = Modifier.weight(1f),
                                    destructive = true
                                )
                            }
                        }
                    }
                }
            }
        }

        if (bulkProgress.isRunning) {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.padding(28.dp).widthIn(max = 320.dp).fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(48.dp), color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        val progressLabel = bulkProgress.labelRes?.let { stringResource(it) }.orEmpty()
                        Text(stringResource(R.string.progress_running, progressLabel), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = {
                                if (bulkProgress.total > 0) (bulkProgress.current.toFloat() / bulkProgress.total).coerceIn(0f, 1f) else 0f
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("${bulkProgress.current} / ${bulkProgress.total}", style = MaterialTheme.typography.bodySmall)
                        if (shareState.isPreparing) {
                            if (bulkProgress.total > 1) {
                                Text(
                                    stringResource(R.string.shelf_share_v21_bundle_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                            TextButton(onClick = viewModel::cancelSharePreparation) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    }
                }
            }
        }
    }

    if (isActive && folderAssignment.isOpen) {
        ShelfFolderAssignmentDialog(folderAssignment, folderViewModel)
    }

    if (isActive && showLanguageDialog) {
        val currentTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val selectedLanguage = when {
            currentTags.startsWith("zh", ignoreCase = true) -> LanguageChoice.SIMPLIFIED_CHINESE
            currentTags.startsWith("en", ignoreCase = true) -> LanguageChoice.ENGLISH
            else -> LanguageChoice.SYSTEM
        }
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.language)) },
            text = {
                Column {
                    LanguageChoice.entries.forEach { choice ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val locales = if (choice.languageTags.isEmpty()) {
                                        LocaleListCompat.getEmptyLocaleList()
                                    } else {
                                        LocaleListCompat.forLanguageTags(choice.languageTags)
                                    }
                                    AppCompatDelegate.setApplicationLocales(locales)
                                    showLanguageDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = choice == selectedLanguage,
                                onClick = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(choice.labelRes))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    showActionMenu?.takeIf { isActive }?.let { comic ->
        val currentJmId = comic.jmId.trim()
            .takeIf { it.toLongOrNull()?.let { value -> value > 0 } == true }
        val hasDuplicateJmId = currentJmId != null && currentJmId in duplicateJmIds
        val enteredJmId = bindText.trim()
        val enteredJmIdIsValid = enteredJmId.toLongOrNull()?.let { it > 0 } == true
        val conflictingComic = if (enteredJmIdIsValid) {
            allComics.firstOrNull {
                it.id != comic.id && it.jmId.trim() == enteredJmId
            }
        } else {
            null
        }

        fun closeActionDialog() {
            showActionMenu = null
            actionDialogMode = ComicActionDialogMode.ACTIONS
            bindText = ""
        }

        AlertDialog(
            onDismissRequest = { closeActionDialog() },
            title = {
                Text(
                    when (actionDialogMode) {
                        ComicActionDialogMode.ACTIONS -> comic.name
                        ComicActionDialogMode.BIND_JM_ID -> stringResource(R.string.bind_jm_id)
                        ComicActionDialogMode.DELETE_CONFIRMATION -> stringResource(R.string.delete_confirm_title)
                    },
                    maxLines = 4
                )
            },
            text = {
                when (actionDialogMode) {
                    ComicActionDialogMode.ACTIONS -> {
                        Column {
                            Text(stringResource(R.string.choose_action), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                currentJmId?.let { stringResource(R.string.current_jm_id, it) }
                                    ?: stringResource(R.string.current_jm_id_none),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (hasDuplicateJmId) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    stringResource(R.string.duplicate_jm_id_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            TextButton(onClick = {
                                bindText = currentJmId.orEmpty()
                                actionDialogMode = ComicActionDialogMode.BIND_JM_ID
                            }) {
                                Text(stringResource(if (currentJmId == null) R.string.bind_jm_id else R.string.modify_jm_id))
                            }
                        }
                    }

                    ComicActionDialogMode.BIND_JM_ID -> {
                        Column {
                            Text(
                                if (hasDuplicateJmId) {
                                    stringResource(R.string.duplicate_jm_id_prompt, currentJmId.orEmpty())
                                } else {
                                    stringResource(R.string.enter_jm_id_prompt)
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = bindText,
                                onValueChange = { bindText = it.filter(Char::isDigit) },
                                placeholder = { Text(stringResource(R.string.jm_id_hint)) },
                                singleLine = true
                            )
                            if (conflictingComic != null) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    stringResource(R.string.jm_id_conflict, conflictingComic.name),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    ComicActionDialogMode.DELETE_CONFIRMATION -> {
                        Text(stringResource(R.string.delete_confirm_message, comic.name))
                    }
                }
            },
            confirmButton = {
                when (actionDialogMode) {
                    ComicActionDialogMode.ACTIONS -> {
                        TextButton(onClick = {
                            android.util.Log.d(
                                "JmLink",
                                "书架详情 comicId=${comic.id} jmId='${comic.jmId}' duplicate=$hasDuplicateJmId"
                            )
                            if (currentJmId != null && !hasDuplicateJmId) {
                                closeActionDialog()
                                onJmDetail(currentJmId)
                            } else {
                                bindText = if (hasDuplicateJmId) "" else currentJmId.orEmpty()
                                actionDialogMode = ComicActionDialogMode.BIND_JM_ID
                            }
                        }) {
                            Text(stringResource(R.string.open_jm_details))
                        }
                    }

                    ComicActionDialogMode.BIND_JM_ID -> {
                        TextButton(
                            enabled = enteredJmIdIsValid && conflictingComic == null,
                            onClick = {
                                android.util.Log.d(
                                    "JmLink",
                                    "手动绑定 comicId=${comic.id} jmId=$enteredJmId"
                                )
                                viewModel.bindJmId(comic, enteredJmId)
                                closeActionDialog()
                                onJmDetail(enteredJmId)
                            }
                        ) {
                            Text(stringResource(R.string.bind_and_open))
                        }
                    }

                    ComicActionDialogMode.DELETE_CONFIRMATION -> {
                        TextButton(
                            onClick = {
                                viewModel.deleteComic(comic)
                                closeActionDialog()
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(stringResource(R.string.action_delete))
                        }
                    }
                }
            },
            dismissButton = {
                when (actionDialogMode) {
                    ComicActionDialogMode.ACTIONS -> {
                        TextButton(onClick = {
                            actionDialogMode = ComicActionDialogMode.DELETE_CONFIRMATION
                        }) {
                            Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }

                    ComicActionDialogMode.BIND_JM_ID -> {
                        TextButton(onClick = {
                            actionDialogMode = ComicActionDialogMode.ACTIONS
                        }) {
                            Text(stringResource(R.string.action_back))
                        }
                    }

                    ComicActionDialogMode.DELETE_CONFIRMATION -> {
                        TextButton(onClick = {
                            actionDialogMode = ComicActionDialogMode.ACTIONS
                        }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun ShelfFolderAssignmentDialog(
    state: ShelfFolderAssignmentState,
    viewModel: ShelfFolderAssignmentViewModel
) {
    val enabled = !state.isSaving && !state.isLoading
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text(stringResource(R.string.shelf_folder_v22_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.issue?.let { issue ->
                    Text(
                        stringResource(when (issue) {
                            ShelfFolderIssue.NAME_REQUIRED -> R.string.shelf_folder_v22_name_required
                            ShelfFolderIssue.NAME_EXISTS -> R.string.shelf_folder_v22_name_exists
                            ShelfFolderIssue.FOLDER_MISSING -> R.string.shelf_folder_v22_missing
                            ShelfFolderIssue.NO_COMICS -> R.string.shelf_folder_v22_no_comics
                            ShelfFolderIssue.LOAD_FAILED -> R.string.shelf_folder_v22_load_failed
                            ShelfFolderIssue.SAVE_FAILED -> R.string.shelf_folder_v22_save_failed
                        }),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (issue == ShelfFolderIssue.LOAD_FAILED) {
                        TextButton(onClick = viewModel::reloadFolders) { Text(stringResource(R.string.action_retry)) }
                    }
                }
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            pluralStringResource(R.plurals.selected_count, state.comicIds.size, state.comicIds.size),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            stringResource(R.string.shelf_folder_v22_choose_hint),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    item {
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = viewModel::chooseNewFolder),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = state.creating, onClick = null, enabled = enabled)
                            Text(stringResource(R.string.shelf_folder_v22_new))
                        }
                    }
                    if (state.creating) {
                        item {
                            OutlinedTextField(
                                value = state.newName,
                                onValueChange = viewModel::updateName,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = enabled,
                                singleLine = true,
                                label = { Text(stringResource(R.string.shelf_folder_v22_name)) },
                                isError = state.issue == ShelfFolderIssue.NAME_REQUIRED || state.issue == ShelfFolderIssue.NAME_EXISTS,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { viewModel.save() })
                            )
                        }
                    }
                    item {
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.shelf_folder_v22_existing),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    if (state.isLoading) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp))
                            }
                        }
                    } else if (state.folders.isEmpty()) {
                        item { Text(stringResource(R.string.shelf_folder_v22_empty), style = MaterialTheme.typography.bodySmall) }
                    }
                    listItems(state.folders, key = { it.id }) { folder ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = enabled) { viewModel.chooseFolder(folder.id) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = !state.creating && state.folderId == folder.id, onClick = null, enabled = enabled)
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(folder.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    pluralStringResource(R.plurals.shelf_folder_v22_comic_count, folder.comicCount, folder.comicCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = viewModel::save,
                enabled = enabled && state.comicIds.isNotEmpty() &&
                    (if (state.creating) state.newName.isNotBlank() else state.folders.any { it.id == state.folderId })
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(if (state.creating) R.string.shelf_folder_v22_create_add else R.string.shelf_folder_v22_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismiss, enabled = !state.isSaving) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun ShelfSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    isActive: Boolean
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var searchFocused by remember { mutableStateOf(false) }
    LaunchedEffect(focusRequester) {
        if (isActive) focusRequester.requestFocus()
    }
    LaunchedEffect(isActive) {
        if (!isActive && searchFocused) focusManager.clearFocus()
    }

    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = {
            Text(stringResource(R.string.search_comics_hint), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
            .onFocusChanged { searchFocused = it.isFocused }
    )
}

@Composable
private fun ShelfEmptyState(isSearchActive: Boolean, canImport: Boolean, onImport: () -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            modifier = Modifier.size(64.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(if (isSearchActive) R.drawable.ic_material_search else R.drawable.ic_material_menu_book),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        Text(
            text = stringResource(if (isSearchActive) R.string.no_search_results else R.string.shelf_empty_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        if (!isSearchActive) {
            val interaction = remember { MutableInteractionSource() }
            Button(
                onClick = onImport,
                enabled = canImport,
                modifier = Modifier.pressScale(interaction),
                interactionSource = interaction
            ) {
                Icon(painterResource(R.drawable.ic_material_file_upload), contentDescription = null)
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.shelf_import_zip))
            }
        }
    }
}

@Composable
private fun ShelfSelectionAction(
    label: String,
    icon: Painter,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ImportProgressContent(state: ImportState) {
    Card(
        modifier = Modifier.padding(20.dp).widthIn(max = 440.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.importing_comics), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.shelf_v21_import_processed, state.completed, state.total),
                style = MaterialTheme.typography.titleMedium
            )
            LinearProgressIndicator(
                progress = { if (state.total > 0) state.completed.toFloat() / state.total else 0f },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                stringResource(R.string.shelf_v21_import_current, (state.completed + 1).coerceAtMost(state.total), state.total),
                style = MaterialTheme.typography.bodyMedium
            )
            if (state.currentName.isNotBlank()) {
                Text(state.currentName, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Text(
                stringResource(when (state.phase) {
                    ImportPhase.CHECKING -> R.string.shelf_v21_import_checking
                    ImportPhase.EXTRACTING -> R.string.shelf_v21_import_extracting
                    ImportPhase.SAVING -> R.string.shelf_v21_import_saving
                    else -> R.string.shelf_v21_import_preparing
                }),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.phase == ImportPhase.EXTRACTING || state.phase == ImportPhase.SAVING) {
                Text(
                    stringResource(R.string.shelf_v21_import_extracted, state.extractedImages, state.extractedBytes / 1_048_576.0),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                stringResource(R.string.shelf_v21_import_counts, state.successCount, state.repairedCount, state.skippedCount, state.failedCount),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
            Text(
                stringResource(R.string.shelf_v21_import_background_hint),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun resolveCoverPath(comic: Comic): String {
    val f = File(comic.coverPath)
    if (f.exists()) return comic.coverPath
    val dir = File(comic.directoryPath)
    if (dir.isDirectory) {
        val first = dir.listFiles()?.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif") }?.minByOrNull { it.name }
        if (first != null) return first.absolutePath
    }
    return comic.coverPath
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComicGridItem(comic: Comic, isSelected: Boolean, selectMode: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .semantics { if (selectMode) selected = isSelected }
            .combinedClickable(onClick = onClick, onLongClick = if (selectMode) null else onLongClick),
        shape = shape,
        border = BorderStroke(1.dp, if (isSelected) colors.primary else colors.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) colors.secondaryContainer else colors.surface,
            contentColor = if (isSelected) colors.onSecondaryContainer else colors.onSurface
        )
    ) {
        Box {
            val coverPath = resolveCoverPath(comic)
            Column {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(coverPath).size(360, 512).crossfade(false).build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.7f),
                    contentScale = ContentScale.Crop,
                    loading = {
                        Box(Modifier.fillMaxSize().background(colors.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    },
                    error = {
                        Box(Modifier.fillMaxSize().background(colors.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                            Icon(
                                painterResource(R.drawable.ic_material_menu_book),
                                contentDescription = null,
                                tint = colors.onSurfaceVariant
                            )
                        }
                    }
                )
                Text(
                    text = comic.name,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    minLines = 4,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start
                )
            }
            if (selectMode) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(32.dp),
                    shape = CircleShape,
                    color = colors.surface
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Checkbox(checked = isSelected, onCheckedChange = null)
                    }
                }
            }
        }
    }
}
