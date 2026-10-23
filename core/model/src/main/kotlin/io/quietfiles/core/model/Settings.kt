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
    /** Касание значка файла выбирает его (F4.1). */
    val tapIconToSelect: Boolean = true,
    /** Вход по отпечатку, лицу или PIN-коду устройства (F14, S11). */
    val appLock: Boolean = false,
    /** Не показывать содержимое в списке недавних и на снимках экрана (FLAG_SECURE). */
    val secureScreen: Boolean = false,
    /** Убирать GPS-координаты из фото при отправке (F14, «Конфиденциальность»). */
    val stripLocationOnShare: Boolean = true,
    val onboardingDone: Boolean = false,
) {
    companion object {
        /** Допустимые сроки хранения корзины; 0 — хранить, пока не очистят вручную. */
        val TRASH_RETENTION_OPTIONS = listOf(7, 30, 90, 0)
    }
}
