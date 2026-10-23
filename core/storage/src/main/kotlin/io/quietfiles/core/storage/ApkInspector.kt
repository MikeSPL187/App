package io.quietfiles.core.storage

import android.content.Context
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.quietfiles.core.common.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Сведения об установочном файле (F7.3). */
data class ApkInfo(
    val packageName: String,
    val label: String?,
    val versionName: String?,
    val versionCode: Long,
    /** Версия уже установленного приложения или `null`, если оно не найдено. */
    val installedVersionCode: Long?,
) {
    val status: ApkStatus
        get() = when {
            installedVersionCode == null -> ApkStatus.UNKNOWN
            versionCode > installedVersionCode -> ApkStatus.UPDATE
            versionCode == installedVersionCode -> ApkStatus.INSTALLED
            else -> ApkStatus.OLDER
        }
}

/**
 * Без QUERY_ALL_PACKAGES (раздел 7 ТЗ) видны только приложения из `<queries>` манифеста — с иконкой
 * в лаунчере и открывающие файлы. Поэтому «не найдено» не значит «не установлено»: тогда статус неизвестен.
 */
enum class ApkStatus { UNKNOWN, INSTALLED, UPDATE, OLDER }

@Singleton
class ApkInspector @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val cache = LruCache<String, ApkInfo>(256)

    suspend fun inspect(path: String, lastModified: Long): ApkInfo? = withContext(io) {
        val key = "$path@$lastModified"
        cache.get(key)?.let { return@withContext it }
        val pm = context.packageManager
        val archive = runCatching { pm.getPackageArchiveInfo(path, 0) }.getOrNull() ?: return@withContext null
        val app = archive.applicationInfo
        val label = app?.let {
            // Без этих путей PackageManager не может прочитать ресурсы неустановленного APK.
            it.sourceDir = path
            it.publicSourceDir = path
            runCatching { it.loadLabel(pm).toString() }.getOrNull()
        }
        val installed = try {
            PackageInfoCompat.getLongVersionCode(pm.getPackageInfo(archive.packageName, 0))
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        ApkInfo(
            packageName = archive.packageName,
            label = label?.takeIf { it.isNotBlank() && it != archive.packageName },
            versionName = archive.versionName,
            versionCode = PackageInfoCompat.getLongVersionCode(archive),
            installedVersionCode = installed,
        ).also { cache.put(key, it) }
    }
}
