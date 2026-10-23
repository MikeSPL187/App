package io.quietfiles.core.common

import android.content.Context
import io.quietfiles.core.model.StorageVolumeInfo
import io.quietfiles.core.model.VolumeKind

/** Короткое имя тома для путей в подписях: «Память», «SD-карта» или метка флешки. */
fun Context.volumeShortName(volume: StorageVolumeInfo): String = when (volume.kind) {
    VolumeKind.INTERNAL -> getString(R.string.volume_short_internal)
    VolumeKind.SD_CARD -> volume.label.ifBlank { getString(R.string.volume_short_sd) }
    VolumeKind.USB -> volume.label.ifBlank { getString(R.string.volume_short_usb) }
    VolumeKind.NETWORK -> volume.label
}

/** Путь для людей: «Память/DCIM/Camera» вместо «/storage/emulated/0/DCIM/Camera». */
fun Context.displayPath(path: String, volume: StorageVolumeInfo?): String {
    volume ?: return path
    val root = volume.rootPath.trimEnd('/')
    if (path != root && !path.startsWith("$root/")) return path
    return volumeShortName(volume) + path.removePrefix(root)
}
