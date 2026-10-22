package io.quietfiles.feature.search

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

fun NavGraphBuilder.searchScreen(onBack: () -> Unit, onOpenFolder: (String) -> Unit) {
    composable<SearchRoute> {
        SearchScreen(onBack = onBack, onOpenFolder = onOpenFolder)
    }
}
