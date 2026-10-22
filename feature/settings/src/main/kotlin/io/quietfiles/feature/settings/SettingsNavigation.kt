package io.quietfiles.feature.settings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

fun NavGraphBuilder.settingsScreen(onBack: () -> Unit, onRequestAccess: () -> Unit) {
    composable<SettingsRoute> {
        SettingsScreen(onBack = onBack, onRequestAccess = onRequestAccess)
    }
}
