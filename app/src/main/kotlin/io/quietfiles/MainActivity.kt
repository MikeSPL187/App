package io.quietfiles

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.quietfiles.core.data.repository.SettingsRepository
import io.quietfiles.core.designsystem.theme.QfTheme
import io.quietfiles.core.operations.OperationService
import io.quietfiles.ui.AppRoot
import io.quietfiles.ui.LaunchRequest
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    /** Что открыть по внешнему запросу: ярлык папки или уведомление об операциях. */
    private val launchRequest = mutableStateOf<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
            val s = settings ?: return@setContent
            QfTheme(themeMode = s.themeMode, dynamicColor = s.dynamicColor) {
                AppRoot(
                    settings = s,
                    launchRequest = launchRequest.value,
                    onLaunchRequestHandled = { launchRequest.value = null },
                )
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
            intent.action == ACTION_OPEN_FOLDER -> {
                // Ярлык на рабочем столе. Принимаем только существующую папку: это лишь переход по экрану.
                val path = intent.getStringExtra(EXTRA_PATH)
                if (path != null && File(path).isDirectory) launchRequest.value = LaunchRequest.OpenFolder(path)
            }
        }
    }

    companion object {
        const val ACTION_OPEN_FOLDER = "io.quietfiles.action.OPEN_FOLDER"
        const val EXTRA_PATH = "path"
    }
}
