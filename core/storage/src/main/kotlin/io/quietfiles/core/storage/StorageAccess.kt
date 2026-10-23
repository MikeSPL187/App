package io.quietfiles.core.storage

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.Environment
import androidx.annotation.VisibleForTesting
import android.provider.Settings

/** Доступ «Управление всеми файлами» (MANAGE_EXTERNAL_STORAGE), раздел 7 ТЗ. */
object StorageAccess {
    /** Подмена для тестов: Robolectric не умеет отвечать на запрос о доступе ко всем файлам. */
    @VisibleForTesting
    var accessOverride: Boolean? = null

    fun hasAllFilesAccess(): Boolean = accessOverride ?: Environment.isExternalStorageManager()

    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri())

    /** Запасной экран, если прошивка не поддерживает запрос для конкретного приложения. */
    fun fallbackIntent(): Intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
}
