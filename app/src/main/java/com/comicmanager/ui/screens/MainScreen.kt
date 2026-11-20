package com.comicmanager.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.launch
import com.comicmanager.model.Comic
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

    LaunchedEffect(isActive, folderAssignment.notice?.id) {
        if (!isActive) return@LaunchedEffect
        folderAssignment.notice?.let { notice ->
            val message = when {
                notice.created -> context.getString(R.string.shelf_folder_v22_created_added, notice.folderName)
                notice.addedCount == 0 -> context.getString(R.string.shelf_folder_v22_already_added, notice.folderName)
                else -> context.resources.getQuantityString(
                    R.plurals.shelf_folder_v22_added, notice.addedCount, notice.addedCount, notice.folderName
                )
            }
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
            folderViewModel.acknowledgeNotice(notice.id)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { viewModel.updateSearchQuery(it) },
                            placeholder = { Text(stringResource(R.string.search_comics_hint)) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                focusedTextColor = MaterialTheme.colorScheme.onPrimary,
                                unfocusedTextColor = MaterialTheme.colorScheme.onPrimary,
                                cursorColor = MaterialTheme.colorScheme.onPrimary,
                                focusedIndicatorColor = MaterialTheme.colorScheme.onPrimary,
                                unfocusedIndicatorColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(stringResource(R.string.app_name))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleSearch() }) {
                        Icon(
                            if (isSearchActive) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = stringResource(
                                if (isSearchActive) R.string.close_search else R.string.action_search
                            ),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.sort), tint = MaterialTheme.colorScheme.onPrimary)
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            SortMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(stringResource(mode.labelRes))
                                            if (mode == currentSortMode) { Spacer(Modifier.width(8.dp)); Text("✓", color = MaterialTheme.colorScheme.primary) }
                                        }
                                    },
                                    onClick = { viewModel.setSortMode(mode); showSortMenu = false }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.language)) },
                                onClick = {
                                    showSortMenu = false
                                    showLanguageDialog = true
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(if (selectMode) R.string.cancel_selection else R.string.select_comics)) },
                                onClick = { showSortMenu = false; viewModel.toggleSelectMode() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.batch_import_id_archives)) },
                                enabled = !importState.isImporting && !bulkProgress.isRunning,
                                onClick = { showSortMenu = false; zipPickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed")) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_duplicate_comics), color = MaterialTheme.colorScheme.error) },
                                enabled = !importState.isImporting && !bulkProgress.isRunning,
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
                                enabled = !importState.isImporting && !bulkProgress.isRunning,
                                onClick = {
                                    showSortMenu = false
                                    viewModel.exportAll(context) { msg -> scope.launch { snackbarHostState.showSnackbar(message = msg) } }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.batch_rename_from_zip)) },
                                enabled = !importState.isImporting && !bulkProgress.isRunning,
                                onClick = {
                                    showSortMenu = false
                                    viewModel.renameNumericComics(context) { msg -> scope.launch { snackbarHostState.showSnackbar(message = msg) } }
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        if (importState.isImporting) {
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                ImportProgressContent(importState)
            }
        } else if (displayComics.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (isSearchActive) R.string.no_search_results else R.string.no_comics_sort_hint),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)
                            .shelfDragSelection(
                                enabled = selectMode,
                                orderedIds = orderedComicIds,
                                selectedIds = selectedIds,
                                gridState = gridState,
                                onSelectionChange = viewModel::replaceSelection
                            ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                    PullRefreshIndicator(isRefreshing, pullRefreshState, Modifier.align(Alignment.TopCenter))
                }
                if (selectMode) {
                    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shadowElevation = 8.dp) {
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
                                TextButton(
                                    enabled = canAct,
                                    onClick = {
                                        val liveSelected = allComics.filter { it.id in selectedIds }.map { it.id }
                                        folderViewModel.open(liveSelected)
                                    },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp)
                                ) { Text(stringResource(R.string.shelf_folder_v22_add), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center) }
                                TextButton(
                                    enabled = canAct,
                                    onClick = { viewModel.exportSelected(context) { msg -> scope.launch { snackbarHostState.showSnackbar(message = msg) } } },
                                    modifier = Modifier.weight(1f)
                                ) { Text(stringResource(R.string.action_export)) }
                                TextButton(
                                    enabled = canAct,
                                    onClick = { viewModel.shareSelected(context) },
                                    modifier = Modifier.weight(1f)
                                ) { Text(stringResource(R.string.action_share)) }
                                TextButton(
                                    enabled = canAct,
                                    onClick = { viewModel.deleteSelected(context) { msg -> scope.launch { snackbarHostState.showSnackbar(message = msg) } } },
                                    modifier = Modifier.weight(1f)
                                ) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }

        if (bulkProgress.isRunning) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Card(modifier = Modifier.padding(32.dp).widthIn(max = 300.dp)) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(48.dp), color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        val progressLabel = bulkProgress.labelRes?.let { stringResource(it) }.orEmpty()
                        Text(stringResource(R.string.progress_running, progressLabel), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { bulkProgress.current.toFloat() / bulkProgress.total.toFloat() }, modifier = Modifier.fillMaxWidth())
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
private fun ImportProgressContent(state: ImportState) {
    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp).padding(24.dp),
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = if (selectMode) null else onLongClick),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box {
            val coverPath = resolveCoverPath(comic)
            Column {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(coverPath).size(360, 512).crossfade(false).build(),
                    contentDescription = comic.name,
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
                    contentScale = ContentScale.Crop,
                    loading = { Box(Modifier.fillMaxSize().background(Color.LightGray), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) } },
                    error = { Box(Modifier.fillMaxSize().background(Color.Gray), contentAlignment = Alignment.Center) { Text("📖", style = MaterialTheme.typography.headlineMedium) } }
                )
                Text(comic.name, Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall, maxLines = 4, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
            if (selectMode) {
                Checkbox(checked = isSelected, onCheckedChange = { onClick() }, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp))
            }
        }
    }
}
