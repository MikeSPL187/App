package io.quietfiles.core.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.quietfiles.core.model.StorageVolumeInfo
import io.quietfiles.core.model.VolumeKind
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/** Подключённые тома и их изменения (вставили SD-карту, извлекли флешку). */
@Singleton
class StorageVolumes @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val storageManager = context.getSystemService(StorageManager::class.java)

    fun current(): List<StorageVolumeInfo> = storageManager.storageVolumes
        .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
        .mapNotNull { volume ->
            val dir = volume.directory ?: return@mapNotNull null
            val description = volume.getDescription(context).orEmpty()
            val kind = when {
                volume.isPrimary -> VolumeKind.INTERNAL
                description.contains("usb", ignoreCase = true) -> VolumeKind.USB
                else -> VolumeKind.SD_CARD
            }
            StorageVolumeInfo(
                id = volume.uuid ?: "primary",
                kind = kind,
                label = description,
                rootPath = dir.absolutePath,
                isPrimary = volume.isPrimary,
                isRemovable = volume.isRemovable,
            )
        }
        .sortedWith(compareByDescending<StorageVolumeInfo> { it.isPrimary }.thenBy { it.kind.ordinal })

    val primaryRoot: String get() = Environment.getExternalStorageDirectory().absolutePath

    fun volumeFor(path: String): StorageVolumeInfo? =
        current().filter { path == it.rootPath || path.startsWith(it.rootPath + "/") }.maxByOrNull { it.rootPath.length }

    fun observe(): Flow<List<StorageVolumeInfo>> = callbackFlow {
        trySend(current())
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(current())
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()
}
