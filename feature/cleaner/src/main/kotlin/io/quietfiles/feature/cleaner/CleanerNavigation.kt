package io.quietfiles.feature.cleaner

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.quietfiles.core.model.cleanup.CleanupCategory
import kotlinx.serialization.Serializable

/** Раздел «Инструменты» нижней навигации (F1.1, F13). */
@Serializable
data object CleanerRoute

fun NavGraphBuilder.cleanerScreens(
    onNavigate: (Any) -> Unit,
    onBack: () -> Unit,
    onTool: (ToolLink) -> Unit,
    onSettings: () -> Unit,
    onRequestAccess: () -> Unit,
    onShowInBrowser: (String) -> Unit,
) {
    composable<CleanerRoute> {
        CleanerScreen(
            onOpenDetails = { onNavigate(CleanupDetailRoute(it.name)) },
            onTool = { tool -> if (tool == ToolLink.ANALYSIS) onNavigate(FolderAnalysisRoute()) else onTool(tool) },
            onSettings = onSettings,
            onRequestAccess = onRequestAccess,
        )
    }
    composable<CleanupDetailRoute> { entry ->
        val category = runCatching { CleanupCategory.valueOf(entry.toRoute<CleanupDetailRoute>().category) }.getOrNull()
        if (category == null) onBack() else CleanupDetailScreen(category = category, onBack = onBack)
    }
    composable<FolderAnalysisRoute> {
        FolderAnalysisScreen(onBack = onBack, onOpenFolder = { onNavigate(FolderAnalysisRoute(it)) }, onShowInBrowser = onShowInBrowser)
    }
}
