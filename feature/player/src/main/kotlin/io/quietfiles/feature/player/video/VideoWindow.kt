package io.quietfiles.feature.player.video

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer

/** «Картинка в картинке» (F9.4) поддерживается не на всех устройствах (например, Android Go). */
fun Activity.supportsPip(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

/** Соотношение сторон для окна PiP в допустимых системой пределах 1:2,39 … 2,39:1. */
fun pipParams(width: Int, height: Int, autoEnter: Boolean): PictureInPictureParams {
    val ratio = if (width > 0 && height > 0) {
        val r = width.toDouble() / height
        when {
            r > MAX_RATIO -> Rational(239, 100)
            r < 1 / MAX_RATIO -> Rational(100, 239)
            else -> Rational(width, height)
        }
    } else {
        Rational(16, 9)
    }
    return PictureInPictureParams.Builder().setAspectRatio(ratio).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setAutoEnterEnabled(autoEnter)
            setSeamlessResizeEnabled(true)
        }
    }.build()
}

private const val MAX_RATIO = 2.39

/** Включён ли сейчас режим PiP — тогда прячем весь интерфейс плеера. */
@Composable
fun rememberInPipMode(activity: Activity?): Boolean {
    val component = activity as? ComponentActivity ?: return false
    var inPip by remember { mutableStateOf(component.isInPictureInPictureMode) }
    DisposableEffect(component) {
        val observer = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        component.addOnPictureInPictureModeChangedListener(observer)
        onDispose { component.removeOnPictureInPictureModeChangedListener(observer) }
    }
    return inPip
}

/** Яркость окна плеера (F9.3): меняется только здесь, системная настройка не трогается. */
fun Activity.windowBrightness(): Float = window.attributes.screenBrightness

fun Activity.setWindowBrightness(value: Float) {
    window.attributes = window.attributes.apply { screenBrightness = value }
}

/** Пока окно не задало свою яркость, берём системную, чтобы жест начинался с текущего уровня. */
fun Activity.currentBrightness(): Float {
    val own = windowBrightness()
    if (own >= 0) return own
    val system = runCatching {
        android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
    }.getOrDefault(128)
    return (system / 255f).coerceIn(0f, 1f)
}

const val BRIGHTNESS_SYSTEM = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

/** Громкость музыки 0..1 и обратно. */
class VolumeControl(private val audio: AudioManager) {
    private val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val level: Float get() = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    fun set(level: Float) {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).toInt().coerceIn(0, max), 0)
    }
}

/** Ориентация экрана плеера: по умолчанию по видео, кнопка «Повернуть экран» переключает. */
fun Activity.lockOrientation(landscape: Boolean) {
    requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
}
