package io.quietfiles.feature.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.quietfiles.core.data.repository.SettingsRepository
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.model.ListDensity
import io.quietfiles.core.model.ThemeMode
import io.quietfiles.core.common.DeviceAuth
import io.quietfiles.core.model.UserSettings
import io.quietfiles.core.model.ViewMode
import io.quietfiles.core.storage.StorageAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable
data object SettingsRoute

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val settings: StateFlow<UserSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    val hasAccess = MutableStateFlow(StorageAccess.hasAllFilesAccess())

    val version: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    fun lockAvailable(): Boolean = DeviceAuth.isAvailable(context)

    fun refreshAccess() {
        hasAccess.value = StorageAccess.hasAllFilesAccess()
    }

    fun update(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch { repository.block() }
    }
}

/** Настройки (макет 10). Тема и цвета из обоев доступны всем. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onRequestAccess: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val hasAccess by viewModel.hasAccess.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var retentionDialog by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf(false) }
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshAccess()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(QfIcons.Back, contentDescription = stringResource(R.string.settings_back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { GroupTitle(stringResource(R.string.settings_group_appearance)) }
            item {
                Group {
                    Block {
                        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.settings_theme_free), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val modes = listOf(ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark, ThemeMode.SYSTEM to R.string.theme_system)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            modes.forEachIndexed { i, (mode, label) ->
                                SegmentedButton(
                                    selected = s.themeMode == mode,
                                    onClick = { viewModel.update { setThemeMode(mode) } },
                                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                                ) { Text(stringResource(label)) }
                            }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SwitchRow(
                            stringResource(R.string.settings_dynamic),
                            stringResource(R.string.settings_dynamic_hint),
                            s.dynamicColor,
                        ) { viewModel.update { setDynamicColor(it) } }
                    }
                    Block {
                        Text(stringResource(R.string.settings_density), style = MaterialTheme.typography.bodyLarge)
                        val densities = listOf(ListDensity.COMFORTABLE to R.string.density_comfortable, ListDensity.COMPACT to R.string.density_compact)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            densities.forEachIndexed { i, (d, label) ->
                                SegmentedButton(
                                    selected = s.listDensity == d,
                                    onClick = { viewModel.update { setListDensity(d) } },
                                    shape = SegmentedButtonDefaults.itemShape(i, densities.size),
                                ) { Text(stringResource(label)) }
                            }
                        }
                    }
                }
            }

            item { GroupTitle(stringResource(R.string.settings_group_files)) }
            item {
                Group {
                    SwitchRow(stringResource(R.string.settings_hidden), stringResource(R.string.settings_hidden_hint), s.showHidden) {
                        viewModel.update { setShowHidden(it) }
                    }
                    SwitchRow(stringResource(R.string.settings_folders_first), null, s.defaultSort.foldersFirst) { v ->
                        viewModel.update { setDefaultSort(s.defaultSort.copy(foldersFirst = v)) }
                    }
                    SwitchRow(stringResource(R.string.settings_grid_default), stringResource(R.string.settings_grid_default_hint), s.defaultViewMode == ViewMode.GRID) { v ->
                        viewModel.update { setDefaultViewMode(if (v) ViewMode.GRID else ViewMode.LIST) }
                    }
                    SwitchRow(stringResource(R.string.settings_trash), stringResource(R.string.settings_trash_hint), s.useTrash) {
                        viewModel.update { setUseTrash(it) }
                    }
                    if (s.useTrash) {
                        ValueRow(stringResource(R.string.settings_trash_retention), retentionLabel(s.trashRetentionDays)) { retentionDialog = true }
                    }
                    SwitchRow(stringResource(R.string.settings_confirm_delete), null, s.confirmDelete) {
                        viewModel.update { setConfirmDelete(it) }
                    }
                    SwitchRow(stringResource(R.string.settings_built_in_viewers), stringResource(R.string.settings_built_in_viewers_hint), s.builtInViewers) {
                        viewModel.update { setBuiltInViewers(it) }
                    }
                    SwitchRow(stringResource(R.string.settings_tap_icon), stringResource(R.string.settings_tap_icon_hint), s.tapIconToSelect) {
                        viewModel.update { setTapIconToSelect(it) }
                    }
                }
            }

            item { GroupTitle(stringResource(R.string.settings_group_privacy)) }
            item {
                Group {
                    val lockAvailable = remember { viewModel.lockAvailable() }
                    SwitchRow(
                        stringResource(R.string.settings_app_lock),
                        stringResource(if (lockAvailable) R.string.settings_app_lock_hint else R.string.settings_app_lock_unavailable),
                        s.appLock && lockAvailable,
                        enabled = lockAvailable,
                    ) { viewModel.update { setAppLock(it) } }
                    SwitchRow(stringResource(R.string.settings_secure_screen), stringResource(R.string.settings_secure_screen_hint), s.secureScreen) {
                        viewModel.update { setSecureScreen(it) }
                    }
                    SwitchRow(stringResource(R.string.settings_strip_location), stringResource(R.string.settings_strip_location_hint), s.stripLocationOnShare) {
                        viewModel.update { setStripLocationOnShare(it) }
                    }
                }
            }

            item { GroupTitle(stringResource(R.string.settings_group_access)) }
            item {
                Group {
                    ValueRow(
                        stringResource(R.string.settings_all_files),
                        stringResource(if (hasAccess) R.string.settings_access_granted else R.string.settings_access_denied),
                        onClick = onRequestAccess,
                    )
                    ValueRow(stringResource(R.string.settings_notifications), stringResource(R.string.settings_notifications_hint)) {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    }
                }
            }

            item { GroupTitle(stringResource(R.string.settings_group_about)) }
            item {
                Group {
                    ValueRow(stringResource(R.string.settings_version), viewModel.version, onClick = null)
                    ValueRow(stringResource(R.string.settings_privacy_policy), null) { privacy = true }
                    ValueRow(stringResource(R.string.settings_licenses), null) { licenses = true }
                }
            }
        }
    }

    if (retentionDialog) {
        RetentionDialog(
            current = s.trashRetentionDays,
            onDismiss = { retentionDialog = false },
            onSelect = { days ->
                retentionDialog = false
                viewModel.update { setTrashRetentionDays(days) }
            },
        )
    }
    if (licenses) LicensesDialog { licenses = false }
    if (privacy) PrivacyDialog { privacy = false }
}

@Composable
private fun retentionLabel(days: Int): String =
    if (days <= 0) stringResource(R.string.retention_forever) else pluralStringResource(R.plurals.retention_days, days, days)

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp).clip(MaterialTheme.shapes.large),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) { content() }
}

@Composable
private fun Block(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) { content() }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun ValueRow(title: String, value: String?, onClick: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onClick != null) Icon(QfIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun RetentionDialog(current: Int, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_trash_retention)) },
        text = {
            Column {
                UserSettings.TRASH_RETENTION_OPTIONS.forEach { days ->
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { onSelect(days) }.heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = days == current, onClick = null)
                        Text(retentionLabel(days), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_close)) } },
    )
}

private val Licenses = listOf(
    "AndroidX, Jetpack Compose, Material Components" to "Apache License 2.0",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache License 2.0",
    "Dagger / Hilt" to "Apache License 2.0",
    "Coil" to "Apache License 2.0",
    "Apache Commons Compress" to "Apache License 2.0",
    "XZ for Java" to "0BSD",
    "junrar" to "UnRAR License",
)

@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_licenses)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Licenses.forEach { (name, license) ->
                    Column {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        Text(license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_close)) } },
    )
}

/** Короткая политика: приложение ничего не собирает и не передаёт (раздел 7 ТЗ). */
@Composable
private fun PrivacyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_privacy_policy)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(R.string.privacy_1, R.string.privacy_2, R.string.privacy_3, R.string.privacy_4).forEach {
                    Text(stringResource(it), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_close)) } },
    )
}
