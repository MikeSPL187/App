package io.quietfiles.feature.player

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.quietfiles.feature.player.music.MusicQueueScreen
import io.quietfiles.feature.player.music.MusicScreen
import io.quietfiles.feature.player.video.VideoRoute
import io.quietfiles.feature.player.video.VideoScreen
import kotlinx.serialization.Serializable

/** «Сейчас играет» (F11). */
@Serializable
data object MusicRoute

/** Очередь воспроизведения (F11.1). */
@Serializable
data object MusicQueueRoute

fun NavGraphBuilder.playerScreens(
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenMusic: () -> Unit,
) {
    composable<VideoRoute> { VideoScreen(onBack = onBack, onOpenFolder = onOpenFolder) }
    composable<MusicRoute> { MusicScreen(onBack = onBack, onOpenQueue = onOpenQueue, onOpenFolder = onOpenFolder) }
    composable<MusicQueueRoute> { MusicQueueScreen(onBack = onBack, onOpenPlayer = onOpenMusic) }
}
