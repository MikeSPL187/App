package io.quietfiles.feature.cleaner

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.quietfiles.core.common.IoDispatcher
import io.quietfiles.core.common.displayPath
import io.quietfiles.core.common.volumeShortName
import io.quietfiles.core.designsystem.component.FileTile
import io.quietfiles.core.designsystem.component.rememberFormatters
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.designsystem.theme.QfTheme
import io.quietfiles.core.model.FileCategory
import io.quietfiles.core.model.FileTypes
import io.quietfiles.core.model.cleanup.FolderSize
import io.quietfiles.core.model.cleanup.FolderSizes
import io.quietfiles.core.storage.StorageVolumes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/** Анализ папок (F13.6). Без [path] — список томов. */
@Serializable
data class FolderAnalysisRoute(val path: String? = null)

data class FolderAnalysisState(
    val title: String = "",
    val subtitle: String = "",
    val items: List<FolderSize> = emptyList(),
    val loading: Boolean = true,
) {
    val total: Long get() = items.sumOf { it.bytes }
}

@HiltViewModel
class FolderAnalysisViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val volumes: StorageVolumes,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    val path: String? = savedStateHandle.toRoute<FolderAnalysisRoute>().path

    private val _state = MutableStateFlow(FolderAnalysisState())
    val state: StateFlow<FolderAnalysisState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val items = withContext(io) {
                // Экран закрыли — обход большого тома прекращается сразу.
                val ctx = coroutineContext
                val cancelled = { ctx.ensureActive() }
                if (path == null) {
                    volumes.current().map { v ->
                        val (bytes, files) = FolderSizes.total(File(v.rootPath), cancelled)
                        FolderSize(v.rootPath, context.volumeShortName(v), true, bytes, files)
                    }
                } else {
                    FolderSizes.children(File(path), cancelled)
                }
            }
            val volume = path?.let(volumes::volumeFor)
            val title = when {
                path == null -> context.getString(R.string.cleaner_tool_analysis)
                volume != null && volume.rootPath.trimEnd('/') == path.trimEnd('/') -> context.volumeShortName(volume)
                else -> File(path).name.ifEmpty { path }
            }
            val subtitle = path?.let { context.displayPath(it, volume) }.orEmpty()
            _state.value = FolderAnalysisState(title, subtitle, items, loading = false)
        }
    }
}

/** Дерево по размеру (F13.6): самые тяжёлые папки сверху, касание — внутрь. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderAnalysisScreen(
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onShowInBrowser: (String) -> Unit,
    viewModel: FolderAnalysisViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val formatters = rememberFormatters()
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(QfIcons.Back, stringResource(R.string.cleaner_back)) } },
                title = {
                    Column {
                        Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!state.loading) {
                            Text(
                                listOf(state.subtitle, formatters.size.format(state.total)).filter { it.isNotEmpty() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.StartEllipsis,
                            )
                        }
                    }
                },
                actions = {
                    viewModel.path?.let { path ->
                        IconButton(onClick = { onShowInBrowser(path) }) { Icon(QfIcons.FolderOpen, stringResource(R.string.cleaner_open_in_browser)) }
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.cleaner_counting), style = MaterialTheme.typography.bodyMedium)
                }
            }
            return@Scaffold
        }
        val max = state.items.maxOfOrNull { it.bytes }?.coerceAtLeast(1) ?: 1
        val total = state.total.coerceAtLeast(1)
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            if (state.items.isEmpty()) {
                item { Text(stringResource(R.string.cleaner_folder_empty), modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(state.items, key = { it.path }) { item ->
                val category = if (item.isDirectory) FileCategory.FOLDER else FileTypes.categoryOf(item.name)
                val tone = QfTheme.extended.of(category)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp))
                        .then(if (item.isDirectory) Modifier.clickable(role = Role.Button) { onOpenFolder(item.path) } else Modifier)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FileTile(category, path = item.path.takeIf { !item.isDirectory })
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
                            Text(formatters.size.format(item.bytes), style = MaterialTheme.typography.labelLarge)
                        }
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(item.bytes.toFloat() / max).clip(RoundedCornerShape(3.dp)).background(tone.chart))
                        }
                        Text(
                            buildString {
                                append("${(item.bytes * 100 / total)}%")
                                if (item.isDirectory) append(" · ").append(pluralStringResource(R.plurals.cleaner_files, item.files, item.files))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (item.isDirectory) Icon(QfIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}
