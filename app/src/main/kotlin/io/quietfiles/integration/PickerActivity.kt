package io.quietfiles.integration

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.quietfiles.R
import io.quietfiles.core.data.repository.SettingsRepository
import io.quietfiles.core.designsystem.theme.QfTheme
import io.quietfiles.security.AppLock
import io.quietfiles.security.LockGate
import io.quietfiles.security.SecureWindow
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.model.FileTypes
import io.quietfiles.core.storage.FileIntents
import io.quietfiles.feature.browser.picker.PickerConfig
import io.quietfiles.feature.browser.picker.PickerMode
import io.quietfiles.feature.browser.picker.PickerScreen
import io.quietfiles.ui.openAllFilesSettings
import javax.inject.Inject

/**
 * Выбор файлов для другого приложения: «Прикрепить файл» в мессенджере, почте и т. п. (F15.1).
 * Отдаёт content-URI нашего FileProvider с правом только на чтение и только на выбранные файлы.
 */
@AndroidEntryPoint
class PickerActivity : FragmentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var appLock: AppLock

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val config = configFrom(intent)
        setResult(Activity.RESULT_CANCELED)
        setContent {
            // Ждём настоящие настройки: иначе до их загрузки файлы были бы видны в обход блокировки.
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
            val s = settings ?: return@setContent
            QfTheme(themeMode = s.themeMode, dynamicColor = s.dynamicColor) {
                SecureWindow(s.secureScreen)
                LockGate(enabled = s.appLock, appLock = appLock) {
                    PickerScreen(
                        config = remember { config },
                        onPicked = ::deliver,
                        onCancel = ::finish,
                        onRequestAccess = { openAllFilesSettings(this) },
                    )
                }
            }
        }
    }

    private fun deliver(paths: List<String>) {
        val uris = try {
            paths.map { FileIntents.uriFor(this, it) }
        } catch (e: IllegalArgumentException) {
            // Файл вне разрешённых FileProvider каталогов — не отдаём его.
            Toast.makeText(this, R.string.picker_not_shareable, Toast.LENGTH_SHORT).show()
            return
        }
        if (uris.isEmpty()) return
        val mimes = paths.map { FileTypes.mimeTypeOf(FileNames.nameOf(it)) }
        val result = Intent()
            .setDataAndType(uris.first(), FileIntents.commonMime(mimes))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        result.clipData = ClipData.newUri(contentResolver, FileNames.nameOf(paths.first()), uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun configFrom(intent: Intent): PickerConfig {
        val extra = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.filter { it.isNotBlank() }.orEmpty()
        val type = intent.type?.takeIf { it.isNotBlank() }
        val filters = extra.ifEmpty { listOfNotNull(type) }.filterNot { it == "*/*" }
        return PickerConfig(
            mode = PickerMode.FILES,
            mimeTypes = filters,
            multiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false),
        )
    }
}
