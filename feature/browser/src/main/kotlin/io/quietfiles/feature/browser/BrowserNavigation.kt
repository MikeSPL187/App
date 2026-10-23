package io.quietfiles.feature.browser

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.quietfiles.core.model.FileEntry

/** Подключение экранов обзора к графу навигации приложения. */
fun NavGraphBuilder.browserScreens(
    onOpen: (BrowseRoute) -> Unit,
    onBack: () -> Unit,
    onSearch: (folder: String?) -> Unit,
    onRequestAccess: () -> Unit,
    onPinShortcut: (FileEntry) -> Unit,
    onShowOperations: () -> Unit,
    onSelectionModeChange: (Boolean) -> Unit,
    onEditConnection: (Long) -> Unit = {},
) {
    composable<StorageRootRoute> {
        StorageRootScreen(onOpen = onOpen, onSearch = { onSearch(null) })
    }
    composable<BrowseRoute> {
        BrowserScreen(
            onExit = onBack,
            onSearch = onSearch,
            onRequestAccess = onRequestAccess,
            onPinShortcut = onPinShortcut,
            onShowOperations = onShowOperations,
            onSelectionModeChange = onSelectionModeChange,
            onEditConnection = onEditConnection,
        )
    }
}
