package io.quietfiles.core.operations

import android.content.Context
import androidx.core.net.toUri
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.quietfiles.core.storage.StorageException
import java.io.InputStream
import javax.inject.Inject

/** Откуда читать содержимое, которым поделилось другое приложение. Отдельный интерфейс — ради тестов. */
fun interface ContentSource {
    fun open(uri: String): InputStream
}

internal class ContentResolverSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : ContentSource {
    override fun open(uri: String): InputStream = try {
        context.contentResolver.openInputStream(uri.toUri()) ?: throw StorageException.NotFound(uri)
    } catch (e: SecurityException) {
        // Право на чтение отозвано: например, приложение-источник закрыли после сбоя.
        throw StorageException.AccessDenied(uri, e)
    } catch (e: java.io.FileNotFoundException) {
        throw StorageException.NotFound(uri)
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ContentSourceModule {
    @Binds
    abstract fun bindContentSource(source: ContentResolverSource): ContentSource
}
