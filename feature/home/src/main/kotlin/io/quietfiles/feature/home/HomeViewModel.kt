package io.quietfiles.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.quietfiles.core.model.FileCategory
import io.quietfiles.core.model.FileEntry
import io.quietfiles.core.model.StorageSpace
import io.quietfiles.core.model.StorageVolumeInfo
import io.quietfiles.core.operations.OperationQueue
import io.quietfiles.core.storage.MediaIndex
import io.quietfiles.core.storage.StorageAccess
import io.quietfiles.core.storage.StorageBreakdown
import io.quietfiles.core.storage.StorageProvider
import io.quietfiles.core.storage.StorageVolumes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** Группы для полосы занятого места на главной (макет 01). */
enum class UsageGroup(val categories: Set<FileCategory>) {
    PHOTOS(setOf(FileCategory.IMAGE)),
    VIDEOS(setOf(FileCategory.VIDEO)),
    AUDIO(setOf(FileCategory.AUDIO)),
    DOCUMENTS(setOf(FileCategory.DOCUMENT, FileCategory.SHEET, FileCategory.PRESENTATION, FileCategory.TEXT)),
    ARCHIVES(setOf(FileCategory.ARCHIVE, FileCategory.APK)),
    OTHER(emptySet()),
}

data class VolumeCardState(
    val volume: StorageVolumeInfo,
    val space: StorageSpace?,
    /** `null`, пока разбивка считается или недоступна. */
    val usage: Map<UsageGroup, Long>?,
)

data class HomeUiState(
    val hasAccess: Boolean = true,
    val volumes: List<VolumeCardState> = emptyList(),
    val recent: List<FileEntry> = emptyList(),
    val loading: Boolean = true,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val volumes: StorageVolumes,
    private val provider: StorageProvider,
    private val mediaIndex: MediaIndex,
    queue: OperationQueue,
) : ViewModel() {

    private val refresh = MutableStateFlow(0)

    val state: StateFlow<HomeUiState> = combine(
        volumes.observe(),
        merge(refresh.map { }, queue.finished.map { }).onStart { emit(Unit) },
    ) { vols, _ -> vols }
        .mapLatest { vols -> load(vols) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Вызывается при возвращении на экран: пользователь мог выдать доступ в настройках или удалить файлы. */
    fun refresh() = refresh.update { it + 1 }

    private suspend fun load(vols: List<StorageVolumeInfo>): HomeUiState {
        val access = StorageAccess.hasAllFilesAccess()
        val cards = vols.map { volume ->
            val space = provider.space(volume.rootPath)
            val usage = if (access && space != null) {
                runCatching { mediaIndex.breakdown(volume.rootPath) }.getOrNull()?.toUsage(space)
            } else null
            VolumeCardState(volume, space, usage)
        }
        val recent = if (access) runCatching { mediaIndex.recent(limit = 20) }.getOrDefault(emptyList()) else emptyList()
        return HomeUiState(hasAccess = access, volumes = cards, recent = recent, loading = false)
    }

    private fun StorageBreakdown.toUsage(space: StorageSpace): Map<UsageGroup, Long> {
        val known = UsageGroup.entries.filter { it != UsageGroup.OTHER }.associateWith { group ->
            group.categories.sumOf { bytesByCategory[it] ?: 0L }
        }
        // «Другое» — всё остальное занятое место: приложения, система, файлы вне индекса.
        val other = (space.used - known.values.sum()).coerceAtLeast(0)
        return known + (UsageGroup.OTHER to other)
    }
}
