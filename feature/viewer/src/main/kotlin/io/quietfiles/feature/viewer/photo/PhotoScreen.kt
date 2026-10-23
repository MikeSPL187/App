package io.quietfiles.feature.viewer.photo

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.quietfiles.core.designsystem.component.rememberFormatters
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.model.FileEntry
import io.quietfiles.feature.viewer.R
import java.io.File
import kotlinx.coroutines.flow.distinctUntilChanged

/** Просмотр фото (F8, макет 18): всегда на тёмном фоне, интерфейс скрывается касанием. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoScreen(
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    viewModel: PhotoViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val noApp = stringResource(R.string.photo_no_app)
    var zoom by remember { mutableFloatStateOf(1f) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is PhotoEvent.Launch -> try {
                    context.startActivity(event.intent)
                } catch (e: ActivityNotFoundException) {
                    snackbar.showSnackbar(noApp)
                }
                is PhotoEvent.Message -> snackbar.showSnackbar(event.text)
                is PhotoEvent.OpenFolder -> onOpenFolder(event.path)
                PhotoEvent.Close -> onBack()
            }
        }
    }
    ImmersiveMode(hidden = !state.chromeVisible)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (state.loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            return@Box
        }
        val pager = rememberPagerState(initialPage = state.index) { state.photos.size }
        LaunchedEffect(pager) {
            snapshotFlow { pager.currentPage }.distinctUntilChanged().collect(viewModel::select)
        }
        LaunchedEffect(state.index, state.photos.size) {
            if (pager.currentPage != state.index && state.index < pager.pageCount) {
                if (state.slideshow) pager.animateScrollToPage(state.index) else pager.scrollToPage(state.index)
            }
        }
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            userScrollEnabled = zoom <= 1.01f,
            key = { state.photos.getOrNull(it)?.path ?: it },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val photo = state.photos.getOrNull(page) ?: return@HorizontalPager
            ZoomableBox(
                onTap = viewModel::toggleChrome,
                onScaleChanged = { if (page == pager.currentPage) zoom = it },
                resetKey = page to (pager.currentPage == page),
            ) {
                PhotoImage(photo, state.versions[photo.path], full = true)
            }
        }

        AnimatedVisibility(state.chromeVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            TopBar(state, onBack = onBack, onInfo = { viewModel.showDetails(true) }, onMenu = { menu = true }) {
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.photo_open_with)) }, onClick = { menu = false; viewModel.openWith() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.photo_open_folder)) }, onClick = { menu = false; viewModel.openFolder() })
                }
            }
        }
        if (zoom > 1.01f) {
            Text(
                "${(zoom * 100).toInt()}%",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = if (state.chromeVisible) 170.dp else 32.dp)
                    .clip(RoundedCornerShape(13.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        AnimatedVisibility(state.chromeVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            BottomBar(state, onSelect = { viewModel.select(it) }, viewModel = viewModel)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 180.dp))
    }

    if (state.showDetails) PhotoDetailsSheet(state, viewModel, onOpenFolder = { viewModel.openFolder() })
    if (state.confirmDelete) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(if (state.useTrash) R.string.photo_delete_trash_title else R.string.photo_delete_forever_title)) },
            text = { state.current?.let { Text(it.name) } },
            confirmButton = {
                TextButton(onClick = viewModel::delete) { Text(stringResource(R.string.photo_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDelete) { Text(stringResource(R.string.photo_cancel)) } },
        )
    }
}

@Composable
internal fun PhotoImage(photo: FileEntry, version: Long?, full: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(photo.path, version, full) {
        ImageRequest.Builder(context)
            .data(File(photo.path))
            .memoryCacheKey("${photo.path}#${version ?: photo.lastModified}#$full")
            .diskCacheKey("${photo.path}#${version ?: photo.lastModified}#$full")
            .apply { if (full) size(MAX_DECODE_PX) else size(THUMB_PX) }
            .crossfade(full)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = photo.name,
        contentScale = if (full) ContentScale.Fit else ContentScale.Crop,
        modifier = modifier.fillMaxSize(),
    )
}

@Composable
private fun TopBar(state: PhotoUiState, onBack: () -> Unit, onInfo: () -> Unit, onMenu: () -> Unit, menu: @Composable () -> Unit) {
    val formatters = rememberFormatters()
    Row(
        Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).statusBarsPadding().height(64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(QfIcons.Back, stringResource(R.string.photo_back), tint = Color.White) }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            val photo = state.current
            Text(photo?.name.orEmpty(), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            val date = state.details?.takenAt ?: photo?.lastModified
            Text(
                listOfNotNull(
                    stringResource(R.string.photo_counter, state.index + 1, state.photos.size),
                    date?.let { formatters.date.short(it) },
                ).joinToString(" · "),
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.5.sp,
                maxLines = 1,
            )
        }
        IconButton(onClick = onInfo) { Icon(QfIcons.Info, stringResource(R.string.photo_info), tint = Color.White) }
        Box {
            IconButton(onClick = onMenu) { Icon(QfIcons.More, stringResource(R.string.photo_more), tint = Color.White) }
            menu()
        }
    }
}

@Composable
private fun BottomBar(state: PhotoUiState, onSelect: (Int) -> Unit, viewModel: PhotoViewModel) {
    Column(
        Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).navigationBarsPadding().padding(top = 10.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.photos.size > 1) ThumbnailStrip(state, onSelect)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Action(QfIcons.Share, stringResource(R.string.photo_share), Color.White, viewModel::share)
            Action(QfIcons.Rotate, stringResource(R.string.photo_rotate), Color.White, viewModel::rotate)
            Action(QfIcons.Wallpaper, stringResource(R.string.photo_wallpaper), Color.White, viewModel::setAs)
            if (state.photos.size > 1) Action(QfIcons.Slideshow, stringResource(R.string.photo_slideshow), Color.White, viewModel::startSlideshow)
            Action(QfIcons.Trash, stringResource(R.string.photo_delete), Color(0xFFFFB4AB), viewModel::requestDelete)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Action(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    Column(
        Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, null, tint = color)
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Лента соседних фото: текущее шире и с рамкой. */
@Composable
private fun ThumbnailStrip(state: PhotoUiState, onSelect: (Int) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.index) {
        val visible = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(7)
        listState.animateScrollToItem((state.index - visible / 2).coerceAtLeast(0))
    }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(state.photos, key = { _, p -> p.path }) { i, photo ->
            val current = i == state.index
            val label = stringResource(R.string.photo_counter, i + 1, state.photos.size)
            Box(
                Modifier.size(width = if (current) 56.dp else 36.dp, height = 44.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .then(if (current) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
                    .clickable { onSelect(i) }
                    .semantics { contentDescription = label },
            ) { PhotoImage(photo, state.versions[photo.path], full = false) }
        }
    }
}

/** Полноэкранный режим: без интерфейса прячем и системные панели. */
@Composable
private fun ImmersiveMode(hidden: Boolean) {
    val activity: Activity = LocalActivity.current ?: return
    val controller = remember(activity) { WindowCompat.getInsetsController(activity.window, activity.window.decorView) }
    // Белые значки системных панелей на чёрном фоне; при выходе возвращаем как было.
    DisposableEffect(controller) {
        val lightStatus = controller.isAppearanceLightStatusBars
        val lightNavigation = controller.isAppearanceLightNavigationBars
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = lightStatus
            controller.isAppearanceLightNavigationBars = lightNavigation
        }
    }
    DisposableEffect(hidden) {
        if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
        onDispose { }
    }
}

/** Достаточно для 2,5-кратного увеличения на экране телефона без переполнения памяти. */
private const val MAX_DECODE_PX = 4096
private const val THUMB_PX = 160
