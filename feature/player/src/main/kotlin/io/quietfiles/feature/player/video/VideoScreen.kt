package io.quietfiles.feature.player.video

import io.quietfiles.core.model.network.NetworkPaths
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import io.quietfiles.core.designsystem.component.ImmersiveMode
import io.quietfiles.core.designsystem.component.findActivity
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.designsystem.theme.QfTheme
import io.quietfiles.core.model.FileEntry
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.ThemeMode
import io.quietfiles.core.model.media.PlaybackTime
import io.quietfiles.core.model.media.PlayerGestures
import io.quietfiles.core.storage.FileIntents
import io.quietfiles.feature.player.R
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.abs

/** Видеоплеер (F9, макеты 20, 21). Всегда тёмный, как и просмотр фото. */
@Composable
fun VideoScreen(
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    viewModel: VideoViewModel = hiltViewModel(),
) {
    QfTheme(themeMode = ThemeMode.DARK) {
        VideoContent(onBack, onOpenFolder, viewModel)
    }
}

/** Что сейчас меняет жест по экрану. */
private enum class DragMode { NONE, SEEK, BRIGHTNESS, VOLUME }

@OptIn(UnstableApi::class)
@Composable
private fun VideoContent(onBack: () -> Unit, onOpenFolder: (String) -> Unit, viewModel: VideoViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress by viewModel.progress().collectAsStateWithLifecycle(0L to 0L)
    val context = LocalContext.current
    val activity = context.findActivity()
    val inPip = rememberInPipMode(activity)
    val snackbar = remember { SnackbarHostState() }
    val volume = remember { VolumeControl(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager) }

    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var lockHintVisible by remember { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var audioDialog by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var userLandscape by rememberSaveable { mutableStateOf<Boolean?>(null) }

    // Индикаторы жестов.
    var dragMode by remember { mutableStateOf(DragMode.NONE) }
    var level by remember { mutableFloatStateOf(0f) }
    var seekTarget by remember { mutableStateOf<Long?>(null) }
    var doubleTap by remember { mutableStateOf<Int?>(null) }
    var scrub by remember { mutableStateOf<Long?>(null) }

    val openWithTitle = stringResource(R.string.player_open_with)
    val networkOpenWith = stringResource(R.string.video_network_open_with)
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::addSubtitle)
    }

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    ImmersiveMode(hidden = !controlsVisible || inPip || state.locked)
    KeepScreenOn(state.isPlaying)
    WindowSetup(activity, state, userLandscape)

    // Интерфейс прячется сам через 3,5 с, пока идёт видео.
    LaunchedEffect(controlsVisible, state.isPlaying, interaction, settings) {
        if (controlsVisible && state.isPlaying && !settings) {
            delay(HIDE_DELAY_MS)
            controlsVisible = false
        }
    }
    LaunchedEffect(lockHintVisible) {
        if (lockHintVisible) {
            delay(HIDE_DELAY_MS)
            lockHintVisible = false
        }
    }
    LaunchedEffect(state.resumedFrom) {
        if (state.resumedFrom != null) {
            delay(RESUME_BANNER_MS)
            viewModel.dismissResume()
        }
    }
    LaunchedEffect(doubleTap) {
        if (doubleTap != null) {
            delay(600)
            doubleTap = null
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.onStop()
        // Закрыли окно PiP или ушли из приложения — видео встаёт на паузу.
        viewModel.pause()
    }
    BackHandler(enabled = state.locked) { lockHintVisible = true }

    val currentState by rememberUpdatedState(state)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ContentFrame(
            player = viewModel.player,
            modifier = Modifier.fillMaxSize(),
            contentScale = when (state.scale) {
                VideoScale.FIT -> ContentScale.Fit
                VideoScale.FILL -> ContentScale.Crop
                VideoScale.STRETCH -> ContentScale.FillBounds
            },
        )
        if (inPip) return@Box

        // Слой жестов (F9.3): касание — интерфейс, двойное — ±10 с, движения — перемотка, яркость, громкость.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(state.locked) {
                    detectTapGestures(
                        onTap = {
                            if (currentState.locked) lockHintVisible = !lockHintVisible else controlsVisible = !controlsVisible
                            interaction++
                        },
                        onDoubleTap = if (state.locked) null else { offset ->
                            when (PlayerGestures.zoneOf(offset.x, size.width.toFloat())) {
                                -1 -> {
                                    viewModel.seekBy(-PlayerGestures.DOUBLE_TAP_SEEK_MS)
                                    doubleTap = -1
                                }
                                1 -> {
                                    viewModel.seekBy(PlayerGestures.DOUBLE_TAP_SEEK_MS)
                                    doubleTap = 1
                                }
                                else -> viewModel.togglePlay()
                            }
                        },
                    )
                }
                .pointerInput(state.locked) {
                    if (state.locked) return@pointerInput
                    var startLevel = 0f
                    var startPosition = 0L
                    var dx = 0f
                    var dy = 0f
                    var startX = 0f
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragMode = DragMode.NONE
                            dx = 0f
                            dy = 0f
                            startX = offset.x
                            startPosition = viewModel.player.currentPosition
                        },
                        onDragEnd = {
                            seekTarget?.let(viewModel::seekTo)
                            seekTarget = null
                            dragMode = DragMode.NONE
                        },
                        onDragCancel = {
                            seekTarget = null
                            dragMode = DragMode.NONE
                        },
                    ) { change, amount ->
                        change.consume()
                        dx += amount.x
                        dy += amount.y
                        if (dragMode == DragMode.NONE) {
                            dragMode = when {
                                abs(dx) > abs(dy) -> DragMode.SEEK
                                startX < size.width / 2 -> DragMode.BRIGHTNESS
                                else -> DragMode.VOLUME
                            }
                            startLevel = when (dragMode) {
                                DragMode.BRIGHTNESS -> activity?.currentBrightness() ?: 0.5f
                                DragMode.VOLUME -> volume.level
                                else -> 0f
                            }
                        }
                        when (dragMode) {
                            DragMode.SEEK -> {
                                val duration = currentState.durationMs
                                val target = startPosition + PlayerGestures.seekDelta(dx, size.width.toFloat(), duration)
                                seekTarget = target.coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)
                            }
                            DragMode.BRIGHTNESS -> {
                                level = PlayerGestures.level(startLevel, -dy, size.height * 0.8f)
                                activity?.setWindowBrightness(level.coerceAtLeast(0.01f))
                            }
                            DragMode.VOLUME -> {
                                level = PlayerGestures.level(startLevel, -dy, size.height * 0.8f)
                                volume.set(level)
                            }
                            DragMode.NONE -> Unit
                        }
                    }
                },
        )

        // Индикаторы жестов.
        when (dragMode) {
            DragMode.BRIGHTNESS -> LevelIndicator(QfIcons.Sun, level, stringResource(R.string.video_brightness), Modifier.align(Alignment.CenterStart).padding(start = 40.dp))
            DragMode.VOLUME -> LevelIndicator(
                if (level <= 0.001f) QfIcons.VolumeOff else QfIcons.Volume,
                level,
                stringResource(R.string.video_volume),
                Modifier.align(Alignment.CenterEnd).padding(end = 40.dp),
            )
            else -> Unit
        }
        seekTarget?.let { target ->
            val delta = target - progress.first
            Bubble(
                "${if (delta >= 0) "+" else "−"}${PlaybackTime.format(abs(delta))} · ${PlaybackTime.format(target)}",
                Modifier.align(Alignment.Center),
            )
        }
        doubleTap?.let { side ->
            Bubble(
                stringResource(if (side < 0) R.string.video_back_10 else R.string.video_forward_10),
                Modifier.align(if (side < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 56.dp),
            )
        }

        if (state.buffering && !state.isPlaying && state.error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = Color.White)
        }

        AnimatedVisibility(controlsVisible && !state.locked, enter = fadeIn(), exit = fadeOut()) {
            VideoControls(
                state = state,
                positionMs = scrub ?: progress.first,
                bufferedMs = progress.second,
                supportsPip = activity?.supportsPip() == true,
                onBack = onBack,
                onTogglePlay = {
                    viewModel.togglePlay()
                    interaction++
                },
                onSeekBy = {
                    viewModel.seekBy(it)
                    interaction++
                },
                onSeek = viewModel::seekTo,
                onScrub = {
                    scrub = it
                    interaction++
                },
                onSettings = { settings = true },
                onAudio = { audioDialog = true },
                onPip = { activity?.let { enterPip(it, state) } },
                onLock = {
                    viewModel.setLocked(true)
                    lockHintVisible = true
                },
                onScale = {
                    viewModel.cycleScale()
                    interaction++
                },
                onRotate = {
                    val landscape = userLandscape ?: (state.videoWidth >= state.videoHeight)
                    userLandscape = !landscape
                },
                onNext = viewModel::playNext,
                onOpenWith = {
                    val file = File(state.path)
                    if (NetworkPaths.isNetwork(state.path)) {
                        viewModel.messages.tryEmit(networkOpenWith)
                    } else try {
                        context.startActivity(
                            FileIntents.chooserForView(context, FileEntry(state.path, file.name, false, file.length(), file.lastModified()), openWithTitle),
                        )
                    } catch (e: ActivityNotFoundException) {
                        controlsVisible = true
                    }
                },
                onShowInFolder = { FileNames.parentOf(state.path)?.let(onOpenFolder) },
            )
        }

        // Блокировка экрана (F9.4): касания не действуют, видна только кнопка разблокировки.
        AnimatedVisibility(
            state.locked && lockHintVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(24.dp),
        ) {
            Surface(
                onClick = {
                    viewModel.setLocked(false)
                    controlsVisible = true
                },
                shape = RoundedCornerShape(24.dp),
                color = Color.Black.copy(alpha = 0.6f),
                contentColor = Color.White,
            ) {
                Row(Modifier.height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(QfIcons.Lock, null)
                    Text(stringResource(R.string.video_unlock), modifier = Modifier.padding(start = 10.dp), fontWeight = FontWeight.SemiBold)
                }
            }
        }

        state.resumedFrom?.let { from ->
            ResumeBanner(from, onRestart = viewModel::restart, modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 72.dp))
        }

        state.error?.let { error ->
            ErrorPanel(
                error,
                onOpenWith = {
                    val file = File(state.path)
                    if (NetworkPaths.isNetwork(state.path)) {
                        viewModel.messages.tryEmit(networkOpenWith)
                    } else try {
                        context.startActivity(FileIntents.view(context, FileEntry(state.path, file.name, false, file.length(), file.lastModified())))
                    } catch (e: ActivityNotFoundException) {
                        controlsVisible = true
                    }
                },
                onNext = if (state.next != null) viewModel::playNext else null,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
    }

    if (settings) {
        VideoSettingsSheet(
            state = state,
            viewModel = viewModel,
            onLoadSubtitles = { subtitlePicker.launch(arrayOf("*/*")) },
            onDismiss = { settings = false },
        )
    }
    if (audioDialog) {
        TrackDialog(
            title = stringResource(R.string.video_audio_track),
            options = state.audioTracks,
            onSelect = {
                viewModel.selectAudio(it)
                audioDialog = false
            },
            onDismiss = { audioDialog = false },
        )
    }
}

private fun enterPip(activity: Activity, state: VideoUiState) {
    if (!activity.supportsPip()) return
    runCatching { activity.enterPictureInPictureMode(pipParams(state.videoWidth, state.videoHeight, autoEnter = state.isPlaying)) }
}

/** Ориентация, яркость и PiP: всё возвращается как было, когда плеер закрывают. */
@Composable
private fun WindowSetup(activity: Activity?, state: VideoUiState, userLandscape: Boolean?) {
    activity ?: return
    DisposableEffect(activity) {
        val orientation = activity.requestedOrientation
        onDispose {
            activity.requestedOrientation = orientation
            activity.setWindowBrightness(BRIGHTNESS_SYSTEM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && activity.supportsPip()) {
                runCatching { activity.setPictureInPictureParams(pipParams(0, 0, autoEnter = false)) }
            }
        }
    }
    // Горизонтальное видео — горизонтальный экран, пока пользователь не повернёт сам.
    val landscape = userLandscape ?: if (state.videoWidth > 0) state.videoWidth >= state.videoHeight else null
    LaunchedEffect(landscape) {
        if (landscape != null) activity.lockOrientation(landscape) else activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    // Уходя домой во время просмотра, видео уменьшается в окно (Android 12+ — системой, 11 — по подсказке).
    LaunchedEffect(state.isPlaying, state.videoWidth, state.videoHeight) {
        if (activity.supportsPip() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { activity.setPictureInPictureParams(pipParams(state.videoWidth, state.videoHeight, autoEnter = state.isPlaying)) }
        }
    }
    val current by rememberUpdatedState(state)
    DisposableEffect(activity) {
        val component = activity as? ComponentActivity
        val hint = Runnable {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && current.isPlaying) enterPip(activity, current)
        }
        component?.addOnUserLeaveHintListener(hint)
        onDispose { component?.removeOnUserLeaveHintListener(hint) }
    }
}

@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

/** Столбик яркости или громкости (макет 20). */
@Composable
private fun LevelIndicator(icon: ImageVector, value: Float, label: String, modifier: Modifier) {
    val percent = (value * 100).toInt()
    Column(
        modifier.width(44.dp).height(150.dp).clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha = 0.55f))
            .padding(vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f).width(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.3f))) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(value.coerceIn(0f, 1f)).align(Alignment.BottomCenter).background(Color.White))
        }
        Text("$percent", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Bubble(text: String, modifier: Modifier) {
    Text(
        text,
        color = Color.White,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** «Продолжено с 12:41 · С начала» вместо диалога (F9.5). */
@Composable
private fun ResumeBanner(from: Long, onRestart: () -> Unit, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(QfIcons.ScreenRotation, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
        Text(
            stringResource(R.string.video_resumed_from, PlaybackTime.format(from)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        TextButton(onClick = onRestart) { Text(stringResource(R.string.video_from_start)) }
    }
}

@Composable
private fun ErrorPanel(message: String, onOpenWith: () -> Unit, onNext: (() -> Unit)?, modifier: Modifier) {
    Column(
        modifier.padding(32.dp).clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(QfIcons.Alert, null, tint = Color.White, modifier = Modifier.size(36.dp))
        Text(message, color = Color.White, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenWith) { Text(stringResource(R.string.player_open_with)) }
            if (onNext != null) OutlinedButton(onClick = onNext) { Text(stringResource(R.string.video_next_file)) }
        }
    }
}

private const val HIDE_DELAY_MS = 3_500L
private const val RESUME_BANNER_MS = 6_000L
