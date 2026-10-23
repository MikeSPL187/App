package io.quietfiles.feature.network

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/** Раздел «Сеть» нижней навигации (F16, макет 08). */
@Serializable
data object NetworkRoute

/** Новое подключение или правка сохранённого (макет 09); [id] = 0 — новое. */
@Serializable
data class ConnectionRoute(val id: Long = 0)

fun NavGraphBuilder.networkScreens(
    onNavigate: (Any) -> Unit,
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
) {
    composable<NetworkRoute> {
        NetworkScreen(
            onAdd = { onNavigate(ConnectionRoute()) },
            onEdit = { onNavigate(ConnectionRoute(it)) },
            onOpenFolder = onOpenFolder,
        )
    }
    composable<ConnectionRoute> {
        ConnectionScreen(onBack = onBack, onConnected = onOpenFolder)
    }
}
