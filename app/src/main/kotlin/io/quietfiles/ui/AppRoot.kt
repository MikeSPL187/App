package io.quietfiles.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.quietfiles.MainActivity
import io.quietfiles.integration.ShortcutSigner
import io.quietfiles.R
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.model.FileEntry
import io.quietfiles.core.model.UserSettings
import io.quietfiles.core.storage.FileIntents
import io.quietfiles.core.storage.StorageAccess
import io.quietfiles.feature.browser.BrowseRoute
import io.quietfiles.feature.browser.SourceKind
import io.quietfiles.feature.browser.StorageRootRoute
import io.quietfiles.feature.browser.browserScreens
import io.quietfiles.feature.home.HomeRoute
import io.quietfiles.feature.home.homeScreen
import io.quietfiles.feature.search.SearchRoute
import io.quietfiles.feature.search.searchScreen
import io.quietfiles.feature.settings.SettingsRoute
import io.quietfiles.feature.settings.settingsScreen
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

private data class TopLevel(val route: Any, val routeClass: KClass<*>, val label: Int, val icon: ImageVector)

private val TopLevels = listOf(
    TopLevel(HomeRoute, HomeRoute::class, R.string.nav_home, QfIcons.Home),
    TopLevel(StorageRootRoute, StorageRootRoute::class, R.string.nav_browse, QfIcons.Folder),
    TopLevel(ToolsRoute, ToolsRoute::class, R.string.nav_tools, QfIcons.Tools),
)

/**
 * Оболочка приложения (F1): нижняя навигация на телефоне, навигационная рейка от 600 dp,
 * общий лист «Операции» и сообщения о результатах операций.
 */
@Composable
fun AppRoot(
    settings: UserSettings,
    launchRequest: LaunchRequest?,
    onLaunchRequestHandled: () -> Unit,
    viewModel: AppViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val operations by viewModel.operations.collectAsStateWithLifecycle()
    val hasActive by viewModel.hasActiveOperations.collectAsStateWithLifecycle()
    var showOperations by rememberSaveable { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var onboardingVisible by rememberSaveable { mutableStateOf(!settings.onboardingDone && !StorageAccess.hasAllFilesAccess()) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(hasActive) {
        if (hasActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val scope = rememberCoroutineScope()
    val noAppText = stringResource(R.string.msg_no_app)
    val requestAccess: () -> Unit = { openAllFilesSettings(context) }
    val openFile: (FileEntry) -> Unit = { entry ->
        try {
            context.startActivity(FileIntents.view(context, entry))
        } catch (e: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar(noAppText) }
        }
    }

    LaunchedEffect(launchRequest) {
        when (launchRequest) {
            LaunchRequest.ShowOperations -> showOperations = true
            is LaunchRequest.OpenFolder -> nav.navigate(BrowseRoute(SourceKind.FOLDER, launchRequest.path))
            null -> Unit
        }
        if (launchRequest != null) onLaunchRequestHandled()
    }

    val undoLabel = stringResource(R.string.msg_undo)
    val detailsLabel = stringResource(R.string.msg_details)
    val openLabel = stringResource(R.string.msg_open)
    LaunchedEffect(viewModel) {
        viewModel.messageFlow.collect { message ->
            val label = when (message.action) {
                is AppMessageAction.Undo -> undoLabel
                AppMessageAction.ShowOperations -> detailsLabel
                is AppMessageAction.OpenFolder -> openLabel
                null -> null
            }
            val result = snackbar.showSnackbar(
                message = message.text,
                actionLabel = label,
                withDismissAction = label == null,
                duration = if (message.action is AppMessageAction.Undo) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                when (val action = message.action) {
                    is AppMessageAction.Undo -> viewModel.undoTrash(action.trashIds)
                    AppMessageAction.ShowOperations -> showOperations = true
                    is AppMessageAction.OpenFolder -> nav.navigate(BrowseRoute(SourceKind.FOLDER, action.path))
                    null -> Unit
                }
            }
        }
    }

    if (onboardingVisible) {
        OnboardingScreen(
            onGrant = requestAccess,
            onSkip = {
                onboardingVisible = false
                viewModel.completeOnboarding()
            },
            onGranted = {
                onboardingVisible = false
                viewModel.completeOnboarding()
            },
        )
        return
    }

    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val fullScreen = destination?.hasRoute(SearchRoute::class) == true || destination?.hasRoute(SettingsRoute::class) == true
    val showNav = !fullScreen && !selectionMode

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (!wide) {
                    Column {
                        AnimatedVisibility(visible = hasActive && !showOperations) {
                            OperationsPill(operations) { showOperations = true }
                        }
                        AnimatedVisibility(visible = showNav) {
                            NavigationBar {
                                TopLevels.forEach { item ->
                                    val selected = destination?.hierarchy?.any { it.hasRoute(item.routeClass) } == true
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { nav.navigateTopLevel(item.route) },
                                        icon = { Icon(item.icon, contentDescription = null) },
                                        label = { Text(stringResource(item.label)) },
                                    )
                                }
                            }
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide && showNav) {
                    NavigationRail(Modifier.padding(top = 8.dp)) {
                        Spacer(Modifier.padding(top = 8.dp))
                        TopLevels.forEach { item ->
                            val selected = destination?.hierarchy?.any { it.hasRoute(item.routeClass) } == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { nav.navigateTopLevel(item.route) },
                                icon = { Icon(item.icon, contentDescription = null) },
                                label = { Text(stringResource(item.label)) },
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    AppNavHost(
                        nav = nav,
                        onOpenFile = openFile,
                        onRequestAccess = requestAccess,
                        onShowOperations = { showOperations = true },
                        onSelectionModeChange = { selectionMode = it },
                    )
                    if (wide && hasActive && !showOperations) {
                        Box(Modifier.navigationBarsPadding()) {
                            OperationsPill(operations) { showOperations = true }
                        }
                    }
                }
            }
        }
    }

    if (showOperations) {
        OperationsSheet(
            operations = operations,
            viewModel = viewModel,
            onDismiss = { showOperations = false },
        )
    }
}

@Composable
private fun AppNavHost(
    nav: NavHostController,
    onOpenFile: (FileEntry) -> Unit,
    onRequestAccess: () -> Unit,
    onShowOperations: () -> Unit,
    onSelectionModeChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    NavHost(navController = nav, startDestination = HomeRoute) {
        homeScreen(
            onOpen = { nav.navigate(it) },
            onOpenFile = onOpenFile,
            onSearch = { nav.navigate(SearchRoute()) },
            onSettings = { nav.navigate(SettingsRoute) },
            onRequestAccess = onRequestAccess,
        )
        browserScreens(
            onOpen = { nav.navigate(it) },
            onBack = { nav.popBackStack() },
            onSearch = { folder -> nav.navigate(SearchRoute(folder)) },
            onRequestAccess = onRequestAccess,
            onPinShortcut = { pinFolderShortcut(context, it) },
            onShowOperations = onShowOperations,
            onSelectionModeChange = onSelectionModeChange,
        )
        searchScreen(
            onBack = { nav.popBackStack() },
            onOpenFolder = { nav.navigate(BrowseRoute(SourceKind.FOLDER, it)) },
        )
        settingsScreen(onBack = { nav.popBackStack() }, onRequestAccess = onRequestAccess)
        composable<ToolsRoute> {
            ToolsScreen(onOpen = { nav.navigate(it) }, onSettings = { nav.navigate(SettingsRoute) })
        }
    }
}

/** Переход между разделами без накопления стека: каждый раздел помнит своё состояние. */
private fun NavHostController.navigateTopLevel(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

internal fun openAllFilesSettings(context: Context) {
    try {
        context.startActivity(StorageAccess.requestIntent(context))
    } catch (e: ActivityNotFoundException) {
        runCatching { context.startActivity(StorageAccess.fallbackIntent()) }
    }
}

/** Ярлык папки на рабочем столе (F15.4). */
private fun pinFolderShortcut(context: Context, entry: FileEntry) {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return
    val intent = Intent(context, MainActivity::class.java)
        .setAction(MainActivity.ACTION_OPEN_FOLDER)
        .putExtra(MainActivity.EXTRA_PATH, entry.path)
        .putExtra(MainActivity.EXTRA_SIGNATURE, ShortcutSigner.from(context).sign(entry.path))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    val shortcut = ShortcutInfoCompat.Builder(context, "folder:${entry.path}")
        .setShortLabel(entry.name)
        .setLongLabel(entry.name)
        .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_shortcut_folder))
        .setIntent(intent)
        .build()
    ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
}
