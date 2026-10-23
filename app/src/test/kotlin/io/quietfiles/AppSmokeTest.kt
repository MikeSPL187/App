package io.quietfiles

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Environment
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.quietfiles.core.data.repository.SettingsRepository
import io.quietfiles.core.storage.StorageAccess
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.inject.Inject

/**
 * Дымовой тест всего приложения: настоящий граф Hilt, навигация и экраны поверх файлов в
 * хранилище Robolectric. Проверяет, что основные экраны открываются без падений, и сохраняет
 * их снимки в `app/build/screenshots` для визуальной проверки.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, sdk = [35], qualifiers = "ru-w411dp-h891dp-xxhdpi")
class AppSmokeTest {

    private val hilt = HiltAndroidRule(this)
    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(hilt).around(compose)

    @Inject lateinit var settings: SettingsRepository

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        val root = Environment.getExternalStorageDirectory()
        File(root, "Download").mkdirs()
        File(root, "Download/Отчёт за квартал.pdf").writeBytes(ByteArray(240_000))
        File(root, "Download/photo_2026.jpg").writeBytes(ByteArray(1_200_000))
        File(root, "Download/Архив проекта.zip").writeBytes(ByteArray(5_000))
        File(root, "Documents/Договоры").mkdirs()
        File(root, "Documents/План.txt").writeText("План на неделю")
        File(root, "DCIM/Camera").mkdirs()
        File(root, "Music").mkdirs()
    }

    private fun grantAllFilesAccess() {
        StorageAccess.accessOverride = true
        runBlocking { settings.setOnboardingDone() }
    }

    @After
    fun tearDown() {
        scenario?.close()
        StorageAccess.accessOverride = null
    }

    @Test
    fun mainScreensOpen() {
        grantAllFilesAccess()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
        waitForText("Внутренняя память")
        snapshot("01_home")

        clickText("Обзор")
        waitForText("Загрузки")
        snapshot("02_storage_root")

        clickText("Внутренняя память")
        waitForText("DCIM")
        snapshot("03_volume_root")

        clickText("Download")
        waitForText("photo_2026.jpg")
        snapshot("04_folder")

        waitForText("Отчёт за квартал.pdf")
        compose.onAllNodesWithText("Отчёт за квартал.pdf").onFirst().performTouchInput { longClick() }
        compose.waitForIdle()
        snapshot("05_selection")
        pressBack()
        snapshot("06_selection_closed")

        clickText("Инструменты")
        snapshot("07_tools")

        clickText("Корзина")
        snapshot("08_trash")

        clickText("Главная")
        clickText("Поиск файлов")
        snapshot("09_search")
    }

    @Test
    @Config(qualifiers = "ru-w411dp-h891dp-night-xxhdpi")
    fun darkTheme() {
        grantAllFilesAccess()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
        waitForText("Внутренняя память")
        snapshot("10_home_dark")
        clickText("Обзор")
        clickText("Внутренняя память")
        clickText("Download")
        waitForText("photo_2026.jpg")
        snapshot("11_folder_dark")
    }

    private fun pressBack() {
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun onboardingWithoutAccess() {
        StorageAccess.accessOverride = false
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
        snapshot("00_onboarding")
    }

    /** Данные приходят из Room и файловой системы в фоне, поэтому ждём появления текста. */
    private fun waitForText(text: String) {
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun clickText(text: String) {
        waitForText(text)
        compose.onAllNodesWithText(text).onFirst().performClick()
        compose.waitForIdle()
    }

    private fun snapshot(name: String) {
        compose.waitForIdle()
        var bitmap: Bitmap? = null
        scenario!!.onActivity { activity ->
            val view = activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val dir = File(System.getProperty("user.dir"), "build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
