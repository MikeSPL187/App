package io.quietfiles.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.quietfiles.R
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.storage.StorageAccess
import io.quietfiles.feature.browser.BrowseRoute
import io.quietfiles.feature.browser.SourceKind
import kotlinx.serialization.Serializable

@Serializable
data object ToolsRoute

/**
 * Первый запуск: объясняем, зачем нужен доступ ко всем файлам, до системного экрана (раздел 7 ТЗ).
 * Можно продолжить без доступа — тогда главная покажет напоминание.
 */
@Composable
fun OnboardingScreen(onGrant: () -> Unit, onSkip: () -> Unit, onGranted: () -> Unit) {
    LifecycleResumeEffect(Unit) {
        if (StorageAccess.hasAllFilesAccess()) onGranted()
        onPauseOrDispose { }
    }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.size(24.dp))
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(QfIcons.Folder, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
            }
            Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.onboarding_message), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Point(QfIcons.Folder, stringResource(R.string.onboarding_point_folders))
            Point(QfIcons.Shield, stringResource(R.string.onboarding_point_privacy))
            Point(QfIcons.Settings, stringResource(R.string.onboarding_point_revoke))
            Spacer(Modifier.size(8.dp))
            Button(onClick = onGrant, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(stringResource(R.string.onboarding_grant))
            }
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_skip))
            }
        }
    }
}

@Composable
private fun Point(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Раздел «Инструменты» версии 1.0: корзина, большие файлы, недавние, избранное. Очистка появится в 1.1. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(onOpen: (BrowseRoute) -> Unit, onSettings: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_tools), style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    IconButton(onClick = onSettings) { Icon(QfIcons.Settings, contentDescription = stringResource(R.string.tools_settings)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = 16.dp).clip(MaterialTheme.shapes.large),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ToolRow(QfIcons.Trash, stringResource(R.string.tools_trash), stringResource(R.string.tools_trash_hint)) { onOpen(BrowseRoute(SourceKind.TRASH)) }
            ToolRow(QfIcons.Sparkle, stringResource(R.string.tools_large), stringResource(R.string.tools_large_hint)) { onOpen(BrowseRoute(SourceKind.LARGE_FILES)) }
            ToolRow(QfIcons.Clock, stringResource(R.string.tools_recent), stringResource(R.string.tools_recent_hint)) { onOpen(BrowseRoute(SourceKind.RECENT)) }
            ToolRow(QfIcons.Star, stringResource(R.string.tools_favorites), stringResource(R.string.tools_favorites_hint)) { onOpen(BrowseRoute(SourceKind.FAVORITES)) }
        }
    }
}

@Composable
private fun ToolRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 68.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(QfIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}
