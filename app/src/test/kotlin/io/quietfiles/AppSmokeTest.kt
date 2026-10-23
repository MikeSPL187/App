package io.quietfiles

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Environment
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
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
        val day = 24L * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        listOf(
            "DCIM/Camera/IMG_0001.jpg" to 0L, "DCIM/Camera/IMG_0002.jpg" to 0L, "DCIM/Camera/IMG_0003.jpg" to day,
            "Pictures/Screenshots/Screenshot_1.png" to day, "Pictures/Telegram/photo_1.jpg" to 40 * day,
            "DCIM/Camera/IMG_0004.jpg" to 45 * day, "DCIM/Camera/IMG_0005.jpg" to 45 * day,
        ).forEachIndexed { i, (path, age) -> writeImage(File(root, path), PALETTE[i % PALETTE.size], now - age) }
    }

    /** Однотонная картинка с полосой: миниатюры в галерее должны быть настоящими изображениями. */
    private fun writeImage(file: File, color: Int, modified: Long) {
        file.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(color)
            drawRect(0f, 100f, 160f, 160f, android.graphics.Paint().apply { this.color = 0x33000000 })
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        file.setLastModified(modified)
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
        // «Фото» есть и в легенде карточки памяти; плитка категории — последняя.
        waitForText("Фото")
        compose.onAllNodesWithText("Фото").onLast().performClick()
        waitForText("Сегодня")
        snapshot("09_photos")
        pressBack()

        clickText("Поиск файлов")
        snapshot("10_search")
    }

    @Test
    @Config(qualifiers = "ru-w411dp-h891dp-night-xxhdpi")
    fun darkTheme() {
        grantAllFilesAccess()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
        waitForText("Внутренняя память")
        snapshot("11_home_dark")
        clickText("Обзор")
        clickText("Внутренняя память")
        clickText("Download")
        waitForText("photo_2026.jpg")
        snapshot("12_folder_dark")
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
        val PALETTE = listOf(0xFF4E8C7A.toInt(), 0xFFE0A458.toInt(), 0xFF5B7DB1.toInt(), 0xFFC1666B.toInt(), 0xFF8E7DBE.toInt())
    }
}
