package io.quietfiles.core.operations

import android.content.Context
import io.quietfiles.core.model.FileNames
import io.quietfiles.core.storage.StorageException

/** Заголовки операций: «Копирование 3 объектов», «Распаковка Архив.zip». */
object OperationTitles {
    fun title(context: Context, request: OperationRequest): String {
        val r = context.resources
        return when (request) {
            is OperationRequest.Copy -> r.getQuantityString(R.plurals.op_title_copy, request.sources.size, request.sources.size)
            is OperationRequest.Move -> r.getQuantityString(R.plurals.op_title_move, request.sources.size, request.sources.size)
            is OperationRequest.Delete -> r.getQuantityString(R.plurals.op_title_delete, request.paths.size, request.paths.size)
            is OperationRequest.Trash -> r.getQuantityString(R.plurals.op_title_trash, request.paths.size, request.paths.size)
            is OperationRequest.Restore -> r.getQuantityString(R.plurals.op_title_restore, request.trashIds.size, request.trashIds.size)
            is OperationRequest.Compress -> context.getString(R.string.op_title_compress, FileNames.nameOf(request.target))
            is OperationRequest.Extract -> context.getString(R.string.op_title_extract, FileNames.nameOf(request.archive))
            is OperationRequest.Import ->
                context.getString(R.string.op_title_import, FileNames.nameOf(request.destination))
        }
    }
}

/** Понятные сообщения об ошибках вместо технических исключений. */
object ErrorMessages {
    fun of(context: Context, error: Throwable): String = when (error) {
        is StorageException.NotFound -> context.getString(R.string.op_error_not_found, FileNames.nameOf(error.path))
        is StorageException.AlreadyExists -> context.getString(R.string.op_error_exists, FileNames.nameOf(error.path))
        is StorageException.AccessDenied -> context.getString(R.string.op_error_access, FileNames.nameOf(error.path))
        is StorageException.InvalidName -> context.getString(R.string.op_error_invalid_name)
        is StorageException.NoSpace, is OperationException.NotEnoughSpace -> context.getString(R.string.op_error_no_space)
        is OperationException.SelfCopy -> context.getString(R.string.op_error_self_copy)
        is OperationException.SameLocation -> context.getString(R.string.op_error_same_location)
        is OperationException.ArchiveEncrypted -> context.getString(R.string.op_error_archive_encrypted)
        is OperationException.ArchiveUnsupported -> context.getString(R.string.op_error_archive_unsupported)
        is OperationException.ArchiveCorrupted -> context.getString(R.string.op_error_archive_corrupted)
        is OperationException.UnsafeArchiveEntry -> context.getString(R.string.op_error_archive_unsafe)
        is OperationException.ArchiveTooLarge -> context.getString(R.string.op_error_archive_too_large)
        is java.io.IOException -> if (error.message?.contains("ENOSPC") == true) {
            context.getString(R.string.op_error_no_space)
        } else {
            context.getString(R.string.op_error_io)
        }
        else -> context.getString(R.string.op_error_io)
    }
}
