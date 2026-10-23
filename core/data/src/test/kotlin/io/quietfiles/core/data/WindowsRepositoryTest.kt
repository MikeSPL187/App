package io.quietfiles.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import io.quietfiles.core.data.repository.WindowsRepository
import io.quietfiles.core.model.OpenWindows
import io.quietfiles.core.model.WindowPlace
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WindowsRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun persistsWindowsWithOddNames() = runTest(UnconfinedTestDispatcher()) {
        val store = PreferenceDataStoreFactory.create(scope = TestScope(UnconfinedTestDispatcher(testScheduler))) { File(tmp.root, "w.preferences_pb") }
        val repo = WindowsRepository(store)
        assertThat(repo.windows.first()).isEqualTo(OpenWindows.Initial)
        val odd = WindowPlace("FOLDER", "/s/Папка \"с\" кавычками\tи\nпереносом", title = "Папка")
        repo.update { it.moveCurrent(odd).open(WindowPlace("TRASH")) }
        val restored = WindowsRepository(store).windows.first()
        assertThat(restored.windows.map { it.place }).containsExactly(odd, WindowPlace("TRASH")).inOrder()
        assertThat(restored.current.place.kind).isEqualTo("TRASH")
    }
}
