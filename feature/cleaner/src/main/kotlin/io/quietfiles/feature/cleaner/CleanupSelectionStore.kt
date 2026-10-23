package io.quietfiles.feature.cleaner

import android.content.Intent
import io.quietfiles.core.model.cleanup.CleanupSelection
import io.quietfiles.core.operations.cleanup.CleanupResult
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Выбор пунктов и файлов — один на «Инструменты» и экраны подробностей. Итог очистки тоже
 * общий: подробности закрываются, а сообщение показывает экран пунктов, когда снова виден.
 */
@Singleton
class CleanupSelectionStore @Inject constructor() {
    val selection = MutableStateFlow(CleanupSelection())
    val result = MutableStateFlow<CleanupResult?>(null)
    val systemCache = MutableStateFlow<Intent?>(null)
}
