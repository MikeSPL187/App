package io.quietfiles.core.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class ListDensity { COMFORTABLE, COMPACT }

/** Настройки пользователя (раздел F14 ТЗ). Тема и динамические цвета не зависят от Premium. */
data class UserSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val listDensity: ListDensity = ListDensity.COMFORTABLE,
    val showHidden: Boolean = false,
    val defaultSort: SortOrder = SortOrder(),
    val defaultViewMode: ViewMode = ViewMode.LIST,
    val useTrash: Boolean = true,
    val trashRetentionDays: Int = 30,
    val confirmDelete: Boolean = true,
    val crashReports: Boolean = false,
    val onboardingDone: Boolean = false,
) {
    companion object {
        /** Допустимые сроки хранения корзины; 0 — хранить, пока не очистят вручную. */
        val TRASH_RETENTION_OPTIONS = listOf(7, 30, 90, 0)
    }
}
