package io.quietfiles.feature.home

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.quietfiles.core.model.FileEntry
import io.quietfiles.feature.browser.BrowseRoute
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

fun NavGraphBuilder.homeScreen(
    onOpen: (BrowseRoute) -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onRequestAccess: () -> Unit,
    onCleanup: () -> Unit,
) {
    composable<HomeRoute> {
        HomeScreen(onOpen, onOpenFile, onSearch, onSettings, onRequestAccess, onCleanup)
    }
}
