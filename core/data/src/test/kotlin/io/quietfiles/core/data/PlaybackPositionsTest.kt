package io.quietfiles.core.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.quietfiles.core.data.db.QfDatabase
import io.quietfiles.core.data.repository.PlaybackPositions
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackPositionsTest {

    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), QfDatabase::class.java)

    private val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, QfDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val positions = PlaybackPositions(db.playbackPositions())

    @After
    fun close() = db.close()

    @Test
    fun remembersMiddleAndForgetsEnd() = runTest {
        positions.save("/v.mp4", 761_000, 2_825_000)
        assertThat(positions.get("/v.mp4")).isEqualTo(761_000)
        // Досмотрели до титров — в следующий раз сначала.
        positions.save("/v.mp4", 2_820_000, 2_825_000)
        assertThat(positions.get("/v.mp4")).isNull()
    }

    @Test
    fun shortMusicIsNotRemembered() = runTest {
        positions.save("/song.mp3", 100_000, 240_000, audio = true)
        assertThat(positions.get("/song.mp3")).isNull()
        positions.save("/podcast.mp3", 1_000_000, 3_000_000, audio = true)
        assertThat(positions.get("/podcast.mp3")).isEqualTo(1_000_000)
    }

    @Test
    fun keepsOnlyLatest200() = runTest {
        repeat(205) { positions.save("/v$it.mp4", 60_000, 3_600_000) }
        assertThat(db.playbackPositions().count()).isEqualTo(200)
        assertThat(positions.get("/v204.mp4")).isNotNull()
    }

    @Test
    fun migratesFromVersion1ToLatest() {
        migrations.createDatabase(DB, 1).apply {
            execSQL("INSERT INTO favorites (path, name, isDirectory, addedAt) VALUES ('/a', 'a', 0, 1)")
            close()
        }
        val migrated = migrations.runMigrationsAndValidate(DB, 4, true)
        migrated.query("SELECT COUNT(*) FROM favorites").use {
            it.moveToFirst()
            assertThat(it.getInt(0)).isEqualTo(1)
        }
        migrated.query("SELECT COUNT(*) FROM connections").use {
            it.moveToFirst()
            assertThat(it.getInt(0)).isEqualTo(0)
        }
        migrated.close()
    }

    private companion object {
        const val DB = "migration-test"
    }
}
