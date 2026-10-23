package io.quietfiles.integration

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.quietfiles.R
import io.quietfiles.core.common.IoDispatcher
import io.quietfiles.core.data.repository.SettingsRepository
import io.quietfiles.core.designsystem.component.EmptyState
import io.quietfiles.core.designsystem.component.rememberFormatters
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.designsystem.theme.QfTheme
import io.quietfiles.security.AppLock
import io.quietfiles.security.LockGate
import io.quietfiles.security.SecureWindow
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.OperationSnapshot
import io.quietfiles.core.model.OperationStatus
import io.quietfiles.core.operations.ImportItem
import io.quietfiles.core.operations.OperationQueue
import io.quietfiles.core.operations.OperationRequest
import io.quietfiles.core.storage.FileIntents
import io.quietfiles.ui.openAllFilesSettings
import io.quietfiles.feature.browser.picker.PickerConfig
import io.quietfiles.feature.browser.picker.PickerMode
import io.quietfiles.feature.browser.picker.PickerScreen
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/**
 * «Сохранить в…» из меню «Поделиться» (F15.2): выбор папки, затем копирование.
 * Экран не закрывается, пока идёт копирование: право читать чужие файлы действует,
 * только пока жива активити, получившая их.
 */
@AndroidEntryPoint
class SaveActivity : FragmentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var appLock: AppLock

    private val viewModel: SaveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        viewModel.prepare(SharedContent.from(intent, setOf(FileIntents.authority(this))))
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
            val s = settings ?: return@setContent
            QfTheme(themeMode = s.themeMode, dynamicColor = s.dynamicColor) {
                SecureWindow(s.secureScreen)
                LockGate(enabled = s.appLock, appLock = appLock) {
                    SaveRoot(viewModel, onClose = ::finish, onRequestAccess = { openAllFilesSettings(this) })
                }
            }
        }
    }
}

@HiltViewModel
class SaveViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val queue: OperationQueue,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    sealed interface Stage {
        data object Preparing : Stage
        data object Nothing : Stage
        data class Choose(val items: List<ImportItem>) : Stage
        data class Saving(val operationId: Long, val destination: String) : Stage
    }

    private val stage = MutableStateFlow<Stage>(Stage.Preparing)
    val stageFlow: StateFlow<Stage> = stage.asStateFlow()

    /** Операция сохранения, когда она уже в очереди. */
    val operation: StateFlow<OperationSnapshot?> = combine(stage, queue.operations) { s, ops ->
        (s as? Stage.Saving)?.let { saving -> ops.firstOrNull { it.id == saving.operationId } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun prepare(content: SharedContent) {
        if (stage.value != Stage.Preparing) return
        if (content.isEmpty) {
            stage.value = Stage.Nothing
            return
        }
        viewModelScope.launch {
            val textName = context.getString(
                R.string.save_text_name,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date()).replace(':', '.'),
            )
            val items = withContext(io) { content.toImportItems(context.contentResolver, textName) }
            stage.value = if (items.isEmpty()) Stage.Nothing else Stage.Choose(items)
        }
    }

    fun save(destination: String) {
        val items = (stage.value as? Stage.Choose)?.items ?: return
        val id = queue.enqueue(OperationRequest.Import(items, destination))
        stage.value = Stage.Saving(id, destination)
    }

    fun cancel() {
        (stage.value as? Stage.Saving)?.let { queue.cancel(it.operationId) }
    }
}

@Composable
private fun SaveRoot(viewModel: SaveViewModel, onClose: () -> Unit, onRequestAccess: () -> Unit) {
    val stage by viewModel.stageFlow.collectAsStateWithLifecycle()
    when (val s = stage) {
        SaveViewModel.Stage.Preparing -> Surface(Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        SaveViewModel.Stage.Nothing -> Surface(Modifier.fillMaxSize()) {
            Box(Modifier.safeDrawingPadding(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = QfIcons.Alert,
                    title = stringResource(R.string.save_nothing_title),
                    message = stringResource(R.string.save_nothing_message),
                    actionLabel = stringResource(R.string.save_close),
                    onAction = onClose,
                )
            }
        }
        is SaveViewModel.Stage.Choose -> {
            val count = s.items.size
            val subtitle = if (count == 1) {
                s.items.first().name
            } else {
                pluralStringResource(R.plurals.save_items, count, count)
            }
            PickerScreen(
                config = PickerConfig(PickerMode.FOLDER),
                onPicked = { viewModel.save(it.first()) },
                onCancel = onClose,
                onRequestAccess = onRequestAccess,
                confirmLabel = stringResource(R.string.save_here),
                subtitle = subtitle,
            )
        }
        is SaveViewModel.Stage.Saving -> {
            val op by viewModel.operation.collectAsStateWithLifecycle()
            SavingScreen(op, FileNames.nameOf(s.destination), onCancel = viewModel::cancel, onClose = onClose)
        }
    }
}

@Composable
private fun SavingScreen(op: OperationSnapshot?, folderName: String, onCancel: () -> Unit, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val formatters = rememberFormatters()
    val status = op?.status
    val done = status == OperationStatus.COMPLETED && op.failedItems == 0
    val savedText = stringResource(R.string.save_done, folderName)
    LaunchedEffect(done, status) {
        when {
            done -> {
                Toast.makeText(context, savedText, Toast.LENGTH_SHORT).show()
                onClose()
            }
            status == OperationStatus.CANCELLED -> onClose()
        }
    }
    // Копирование в фоне невозможно (см. описание активити), поэтому «Назад» только сворачивает экран.
    BackHandler { (context as? ComponentActivity)?.moveTaskToBack(true) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            val failed = status == OperationStatus.FAILED || (status == OperationStatus.COMPLETED && op.failedItems > 0)
            Text(
                when {
                    failed -> stringResource(R.string.save_failed)
                    status == OperationStatus.QUEUED -> stringResource(R.string.save_queued)
                    else -> stringResource(R.string.save_progress, folderName)
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            val p = op?.progress
            if (!failed) {
                if (p != null && (p.bytesTotal > 0 || p.itemsTotal > 0)) {
                    LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (p != null) {
                val detail = when {
                    failed -> op.errorMessage
                    p.bytesTotal > 0 -> stringResource(R.string.ops_bytes, formatters.size.format(p.bytesDone), formatters.size.format(p.bytesTotal))
                    p.itemsTotal > 0 -> stringResource(R.string.ops_items, p.itemsDone, p.itemsTotal)
                    else -> null
                }
                detail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                p.currentName?.takeIf { !failed }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (failed) {
                    Button(onClick = onClose) { Text(stringResource(R.string.save_close)) }
                } else {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.ops_cancel)) }
                }
            }
        }
    }
}

