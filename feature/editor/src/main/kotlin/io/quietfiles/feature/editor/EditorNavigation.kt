package io.quietfiles.feature.editor

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

fun NavGraphBuilder.editorScreen(onBack: () -> Unit, onOpenExternally: (String) -> Unit) {
    composable<EditorRoute> { EditorScreen(onBack = onBack, onOpenExternally = onOpenExternally) }
}
