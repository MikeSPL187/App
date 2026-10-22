package io.quietfiles.core.operations

import io.quietfiles.core.data.db.TrashDao
import io.quietfiles.core.data.db.TrashEntity
import io.quietfiles.core.model.Conflict
import io.quietfiles.core.model.ConflictChoice
import io.quietfiles.core.model.ConflictDecision
import io.quietfiles.core.model.OperationProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeTrashDao : TrashDao {
    private val rows = MutableStateFlow<List<TrashEntity>>(emptyList())
    private var nextId = 1L

    override fun observeAll(): Flow<List<TrashEntity>> = rows
    override suspend fun byIds(ids: List<Long>) = rows.value.filter { it.id in ids }
    override suspend fun olderThan(before: Long) = rows.value.filter { it.deletedAt < before }
    override suspend fun all() = rows.value
    override suspend fun insert(entity: TrashEntity): Long {
        val id = nextId++
        rows.value = rows.value + entity.copy(id = id)
        return id
    }
    override suspend fun delete(ids: List<Long>) {
        rows.value = rows.value.filterNot { it.id in ids }
    }
}

class FakeControl(private val choices: ArrayDeque<ConflictDecision> = ArrayDeque()) : OperationControl {
    var progress = OperationProgress()
    val conflicts = mutableListOf<Conflict>()
    val tempFiles = mutableListOf<String>()

    override suspend fun checkpoint() = Unit
    override fun progress(update: (OperationProgress) -> OperationProgress) {
        progress = update(progress)
    }
    override suspend fun decide(conflict: Conflict): ConflictDecision {
        conflicts += conflict
        return choices.removeFirstOrNull() ?: ConflictDecision(ConflictChoice.SKIP, false)
    }
    override fun setTempFile(path: String?) {
        if (path != null) tempFiles += path
    }
}
