package io.quietfiles.core.storage

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.Environment
import android.provider.Settings

/** Доступ «Управление всеми файлами» (MANAGE_EXTERNAL_STORAGE), раздел 7 ТЗ. */
object StorageAccess {
    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri())

    /** Запасной экран, если прошивка не поддерживает запрос для конкретного приложения. */
    fun fallbackIntent(): Intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
}
