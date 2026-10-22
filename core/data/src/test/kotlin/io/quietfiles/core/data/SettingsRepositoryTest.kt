package io.quietfiles.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import io.quietfiles.core.data.repository.SettingsRepository
import io.quietfiles.core.model.SortField
import io.quietfiles.core.model.SortOrder
import io.quietfiles.core.model.ThemeMode
import io.quietfiles.core.model.UserSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun defaultsFollowSystemThemeAndUseTrash() = runTest(UnconfinedTestDispatcher()) {
        val repo = repository(this)
        val settings = repo.settings.first()
        assertThat(settings).isEqualTo(UserSettings())
        assertThat(settings.themeMode).isEqualTo(ThemeMode.SYSTEM)
        assertThat(settings.useTrash).isTrue()
    }

    @Test
    fun persistsChanges() = runTest(UnconfinedTestDispatcher()) {
        val repo = repository(this)
        repo.setThemeMode(ThemeMode.DARK)
        repo.setShowHidden(true)
        repo.setDefaultSort(SortOrder(SortField.SIZE, ascending = false, foldersFirst = false))
        repo.setTrashRetentionDays(90)
        val s = repo.settings.first()
        assertThat(s.themeMode).isEqualTo(ThemeMode.DARK)
        assertThat(s.showHidden).isTrue()
        assertThat(s.defaultSort).isEqualTo(SortOrder(SortField.SIZE, ascending = false, foldersFirst = false))
        assertThat(s.trashRetentionDays).isEqualTo(90)
    }

    private fun repository(scope: TestScope): SettingsRepository {
        val file = File(tmp.root, "settings-${System.nanoTime()}.preferences_pb")
        return SettingsRepository(PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { file })
    }
}
