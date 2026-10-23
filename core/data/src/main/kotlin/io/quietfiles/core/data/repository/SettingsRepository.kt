package io.quietfiles.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.quietfiles.core.model.ListDensity
import io.quietfiles.core.model.SortOrder
import io.quietfiles.core.model.ThemeMode
import io.quietfiles.core.model.UserSettings
import io.quietfiles.core.model.ViewMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Настройки F14 в DataStore. Значения по умолчанию — в [UserSettings]. */
@Singleton
class SettingsRepository @Inject constructor(
    private val store: DataStore<Preferences>,
) {
    private object Keys {
        val theme = stringPreferencesKey("theme_mode")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val density = stringPreferencesKey("list_density")
        val hidden = booleanPreferencesKey("show_hidden")
        val sortField = stringPreferencesKey("sort_field")
        val sortAsc = booleanPreferencesKey("sort_ascending")
        val foldersFirst = booleanPreferencesKey("folders_first")
        val viewMode = stringPreferencesKey("view_mode")
        val useTrash = booleanPreferencesKey("use_trash")
        val trashDays = intPreferencesKey("trash_retention_days")
        val confirmDelete = booleanPreferencesKey("confirm_delete")
        val tapIcon = booleanPreferencesKey("tap_icon_to_select")
        val builtInViewers = booleanPreferencesKey("built_in_viewers")
        val contentIndexing = booleanPreferencesKey("content_indexing")
        val editorFontSize = intPreferencesKey("editor_font_size")
        val editorWrap = booleanPreferencesKey("editor_wrap")
        val editorLineNumbers = booleanPreferencesKey("editor_line_numbers")
        val appLock = booleanPreferencesKey("app_lock")
        val secureScreen = booleanPreferencesKey("secure_screen")
        val stripLocation = booleanPreferencesKey("strip_location_on_share")
        val onboarding = booleanPreferencesKey("onboarding_done")
    }

    val settings: Flow<UserSettings> = store.data.map { p ->
        val d = UserSettings()
        UserSettings(
            themeMode = p[Keys.theme].toEnum(d.themeMode),
            dynamicColor = p[Keys.dynamic] ?: d.dynamicColor,
            listDensity = p[Keys.density].toEnum(d.listDensity),
            showHidden = p[Keys.hidden] ?: d.showHidden,
            defaultSort = SortOrder(
                field = p[Keys.sortField].toEnum(d.defaultSort.field),
                ascending = p[Keys.sortAsc] ?: d.defaultSort.ascending,
                foldersFirst = p[Keys.foldersFirst] ?: d.defaultSort.foldersFirst,
            ),
            defaultViewMode = p[Keys.viewMode].toEnum(d.defaultViewMode),
            useTrash = p[Keys.useTrash] ?: d.useTrash,
            trashRetentionDays = p[Keys.trashDays] ?: d.trashRetentionDays,
            confirmDelete = p[Keys.confirmDelete] ?: d.confirmDelete,
            tapIconToSelect = p[Keys.tapIcon] ?: d.tapIconToSelect,
            builtInViewers = p[Keys.builtInViewers] ?: d.builtInViewers,
            contentIndexing = p[Keys.contentIndexing] ?: d.contentIndexing,
            editorFontSize = p[Keys.editorFontSize] ?: d.editorFontSize,
            editorWrap = p[Keys.editorWrap] ?: d.editorWrap,
            editorLineNumbers = p[Keys.editorLineNumbers] ?: d.editorLineNumbers,
            appLock = p[Keys.appLock] ?: d.appLock,
            secureScreen = p[Keys.secureScreen] ?: d.secureScreen,
            stripLocationOnShare = p[Keys.stripLocation] ?: d.stripLocationOnShare,
            onboardingDone = p[Keys.onboarding] ?: d.onboardingDone,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[Keys.theme] = mode.name }
    }
    suspend fun setDynamicColor(enabled: Boolean) {
        store.edit { it[Keys.dynamic] = enabled }
    }
    suspend fun setListDensity(density: ListDensity) {
        store.edit { it[Keys.density] = density.name }
    }
    suspend fun setShowHidden(show: Boolean) {
        store.edit { it[Keys.hidden] = show }
    }
    suspend fun setDefaultSort(sort: SortOrder) {
        store.edit {
            it[Keys.sortField] = sort.field.name
            it[Keys.sortAsc] = sort.ascending
            it[Keys.foldersFirst] = sort.foldersFirst
        }
    }
    suspend fun setDefaultViewMode(mode: ViewMode) {
        store.edit { it[Keys.viewMode] = mode.name }
    }
    suspend fun setUseTrash(enabled: Boolean) {
        store.edit { it[Keys.useTrash] = enabled }
    }
    suspend fun setTrashRetentionDays(days: Int) {
        store.edit { it[Keys.trashDays] = days }
    }
    suspend fun setConfirmDelete(enabled: Boolean) {
        store.edit { it[Keys.confirmDelete] = enabled }
    }
    suspend fun setContentIndexing(enabled: Boolean) {
        store.edit { it[Keys.contentIndexing] = enabled }
    }

    suspend fun setBuiltInViewers(enabled: Boolean) {
        store.edit { it[Keys.builtInViewers] = enabled }
    }
    suspend fun setEditorFontSize(sp: Int) {
        store.edit { it[Keys.editorFontSize] = sp.coerceIn(UserSettings.EDITOR_FONT_SIZES) }
    }
    suspend fun setEditorWrap(enabled: Boolean) {
        store.edit { it[Keys.editorWrap] = enabled }
    }
    suspend fun setEditorLineNumbers(enabled: Boolean) {
        store.edit { it[Keys.editorLineNumbers] = enabled }
    }
    suspend fun setTapIconToSelect(enabled: Boolean) {
        store.edit { it[Keys.tapIcon] = enabled }
    }
    suspend fun setAppLock(enabled: Boolean) {
        store.edit { it[Keys.appLock] = enabled }
    }
    suspend fun setSecureScreen(enabled: Boolean) {
        store.edit { it[Keys.secureScreen] = enabled }
    }
    suspend fun setStripLocationOnShare(enabled: Boolean) {
        store.edit { it[Keys.stripLocation] = enabled }
    }
    suspend fun setOnboardingDone() {
        store.edit { it[Keys.onboarding] = true }
    }

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
}
