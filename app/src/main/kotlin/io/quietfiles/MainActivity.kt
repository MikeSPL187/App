package io.quietfiles

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.quietfiles.core.data.repository.SettingsRepository
import io.quietfiles.core.designsystem.theme.QfTheme
import io.quietfiles.security.AppLock
import io.quietfiles.security.LockGate
import io.quietfiles.security.SecureWindow
import io.quietfiles.core.operations.OperationService
import io.quietfiles.feature.player.music.MusicPlayer
import io.quietfiles.integration.ShortcutSigner
import io.quietfiles.ui.AppRoot
import io.quietfiles.ui.LaunchRequest
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var appLock: AppLock

    /** Что открыть по внешнему запросу: ярлык папки, уведомление об операциях или о музыке. */
    private val launchRequest = mutableStateOf<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
            val s = settings ?: return@setContent
            QfTheme(themeMode = s.themeMode, dynamicColor = s.dynamicColor) {
                SecureWindow(s.secureScreen)
                LockGate(enabled = s.appLock, appLock = appLock) {
                    AppRoot(
                        settings = s,
                        launchRequest = launchRequest.value,
                        onLaunchRequestHandled = { launchRequest.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when {
            intent.getBooleanExtra(OperationService.EXTRA_SHOW_OPERATIONS, false) ->
                launchRequest.value = LaunchRequest.ShowOperations
            intent.action == MusicPlayer.ACTION_OPEN_PLAYER -> launchRequest.value = LaunchRequest.OpenPlayer
            intent.action == ACTION_OPEN_FOLDER -> {
                // Ярлык на рабочем столе. Принимаем только наш подписанный ярлык и только существующую папку.
                val path = intent.getStringExtra(EXTRA_PATH)
                val signed = path != null && ShortcutSigner.from(this).verify(path, intent.getStringExtra(EXTRA_SIGNATURE))
                if (signed && File(path).isDirectory) launchRequest.value = LaunchRequest.OpenFolder(path)
            }
        }
    }

    companion object {
        const val ACTION_OPEN_FOLDER = "io.quietfiles.action.OPEN_FOLDER"
        const val EXTRA_PATH = "path"
        const val EXTRA_SIGNATURE = "signature"
    }
}
