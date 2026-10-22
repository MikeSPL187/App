package io.quietfiles.feature.browser

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

/** Что показывает экран обзора. */
@Keep
enum class SourceKind {
    FOLDER,
    ARCHIVE,
    PHOTOS,
    VIDEOS,
    AUDIO,
    DOCUMENTS,
    ARCHIVES,
    APKS,
    RECENT,
    FAVORITES,
    TRASH,
    LARGE_FILES,
    ;

    val isLibrary: Boolean
        get() = this != FOLDER && this != ARCHIVE && this != TRASH && this != FAVORITES

    val isPhysical: Boolean get() = this == FOLDER
}

/**
 * Маршрут экрана обзора. Для папок и архивов переходы внутри идут без новой записи в стеке навигации:
 * история хранится во ViewModel, чтобы «Назад» и хлебные крошки работали последовательно.
 *
 * [path] — папка или архив; [inner] — путь внутри архива.
 */
@Serializable
data class BrowseRoute(
    val kind: SourceKind,
    val path: String = "",
    val inner: String = "",
)

/** Экран со списком хранилищ, избранного и корзины — корень раздела «Обзор». */
@Serializable
data object StorageRootRoute
