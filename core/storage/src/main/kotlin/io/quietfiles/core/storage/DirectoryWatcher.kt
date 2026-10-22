package io.quietfiles.core.storage

import android.os.FileObserver
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import java.io.File

/** Сигнал об изменении содержимого папки (F3.9): создание, удаление, переименование, запись. */
fun watchDirectory(path: String): Flow<Unit> = callbackFlow {
    val mask = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or
        FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
    val observer = object : FileObserver(File(path), mask) {
        override fun onEvent(event: Int, name: String?) {
            trySend(Unit)
        }
    }
    observer.startWatching()
    awaitClose { observer.stopWatching() }
}.conflate()
