package io.quietfiles.feature.viewer

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.quietfiles.feature.viewer.photo.PhotoRoute
import io.quietfiles.feature.viewer.photo.PhotoScreen

fun NavGraphBuilder.viewerScreens(onBack: () -> Unit, onOpenFolder: (String) -> Unit) {
    composable<PhotoRoute> { PhotoScreen(onBack = onBack, onOpenFolder = onOpenFolder) }
}
