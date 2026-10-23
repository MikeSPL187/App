package io.quietfiles.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.quietfiles.core.data.db.FavoriteDao
import io.quietfiles.core.data.db.FolderPrefDao
import io.quietfiles.core.data.db.PlaybackPositionDao
import io.quietfiles.core.data.db.QfDatabase
import io.quietfiles.core.data.db.SearchHistoryDao
import io.quietfiles.core.data.db.TrashDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): QfDatabase =
        Room.databaseBuilder(context, QfDatabase::class.java, "quietfiles.db").build()

    @Provides
    @Singleton
    fun preferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    @Provides fun favorites(db: QfDatabase): FavoriteDao = db.favorites()
    @Provides fun searchHistory(db: QfDatabase): SearchHistoryDao = db.searchHistory()
    @Provides fun folderPrefs(db: QfDatabase): FolderPrefDao = db.folderPrefs()
    @Provides fun trash(db: QfDatabase): TrashDao = db.trash()
    @Provides fun playbackPositions(db: QfDatabase): PlaybackPositionDao = db.playbackPositions()
}
