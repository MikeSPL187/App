package io.quietfiles

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Environment
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.imageDecoderEnabled
import coil3.request.allowHardware
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
import java.time.Duration
import android.os.Looper
import org.robolectric.Shadows.shadowOf
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
    val rules: TestRule = RuleChain.outerRule(hilt)
        // Фейковые декодеры Media3: в Robolectric нет настоящих MediaCodec, а плееру нужно «играть».
        .around(ShadowMediaCodecConfig.withAllDefaultSupportedCodecs())
        .around(compose)

    @Inject lateinit var settings: SettingsRepository

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        // В тесте приложение — HiltTestApplication, поэтому загрузчик Coil задаём здесь: Robolectric
        // не умеет ImageDecoder (декодируем через BitmapFactory), а аппаратные битмапы не рисуются
        // на программном Canvas снимка экрана.
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(ApplicationProvider.getApplicationContext<android.content.Context>())
                .imageDecoderEnabled(false)
                .allowHardware(false)
                .build(),
        )
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
        File(root, "DCIM/Camera/IMG_0001.jpg").let { photo ->
            androidx.exifinterface.media.ExifInterface(photo.path).apply {
                setAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE, "Google")
                setAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL, "Pixel 9")
                setAttribute(androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER, "1.7")
                setAttribute(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME, "0.008333")
                setAttribute(androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "200")
                setLatLong(55.7558, 37.6173)
                saveAttributes()
            }
            photo.setLastModified(now)
        }
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
        waitForImages()
        snapshot("09_photos")

        compose.onNodeWithContentDescription("IMG_0001.jpg").performClick()
        waitForText("Повернуть")
        waitForImages()
        snapshot("14_photo_viewer")
        compose.onNodeWithContentDescription("Сведения о фото").performClick()
        waitForText("В фото есть координаты съёмки")
        snapshot("15_photo_details")
        pressBack()
        pressBack()
        pressBack()

        clickText("Поиск файлов")
        snapshot("10_search")
        pressBack()

        waitForText("Поиск файлов")
        compose.onNodeWithContentDescription("Настройки").performClick()
        waitForText("Оформление")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Удалять геоданные при отправке"))
        waitForText("Безопасность и конфиденциальность")
        snapshot("13_settings")
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
    fun editorOpensEditsFindsAndSaves() {
        val yaml = File(Environment.getExternalStorageDirectory(), "Documents/Сервер/config.yaml")
        yaml.parentFile!!.mkdirs()
        yaml.writeText(CONFIG_YAML.replace("\n", "\r\n"))
        grantAllFilesAccess()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        clickText("Обзор")
        clickText("Внутренняя память")
        clickText("Documents")
        clickText("Сервер")
        clickText("config.yaml")
        waitForText("YAML · UTF-8 · CRLF")
        snapshot("20_editor")

        scenario!!.onActivity { activity ->
            val editor = findEditor(activity.window.decorView)
            editor.setSelection(editor.text.indexOf("\"guest\"") + 1)
            editor.text.insert(editor.selectionStart, "new-")
        }
        compose.onNodeWithContentDescription("Найти").performClick()
        compose.onNode(hasSetTextAction() and hasContentDescription("Найти")).performTextInput("name")
        waitForText("1 из 2")
        snapshot("21_editor_search")

        compose.onNodeWithText("Сохранить").performClick()
        waitForText("Сохранено")
        val saved = yaml.readText()
        check(saved.contains("\"new-guest\"")) { saved }
        check(saved.contains("\r\n")) { "line endings were not preserved" }

        clickText("YAML · UTF-8 · CRLF")
        waitForText("Кодировка")
        snapshot("22_editor_params")
    }

    @Test
    fun videoAndMusicPlayers() {
        val root = Environment.getExternalStorageDirectory()
        copyResource("media/sample.mp4", File(root, "Movies/Отпуск_2026_01.mp4"))
        copyResource("media/sample.mp4", File(root, "Movies/Отпуск_2026_02.mp4"))
        copyResource("media/sample.srt", File(root, "Movies/Отпуск_2026_01.srt"))
        copyResource("media/sample.flac", File(root, "Music/Альбом/Утро_live.flac"))
        copyResource("media/sample.wav", File(root, "Music/Альбом/Демо_v3.wav"))
        copyResource("media/cover.jpg", File(root, "Music/Альбом/cover.jpg"))
        grantAllFilesAccess()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        clickText("Обзор")
        clickText("Внутренняя память")
        clickText("Movies")
        clickText("Отпуск_2026_01.mp4")
        waitForText("Далее: Отпуск_2026_02.mp4")
        waitForImages()
        snapshot("30_video_player")
        compose.onNodeWithContentDescription("Субтитры").performClick()
        waitForText("Настройки воспроизведения")
        waitForText("Загрузить файл субтитров")
        snapshot("31_video_settings")
        // «Назад» из теста приходит в Activity, а не в окно листа, — закрывается сразу плеер.
        pressBack()
        waitForText("Отпуск_2026_02.mp4")
        pressBack()

        clickText("Music")
        clickText("Альбом")
        clickText("Утро_live.flac")
        waitForText("Сейчас играет")
        waitForText("FLAC · 24 бит / 96 кГц")
        waitForImages()
        snapshot("32_music_player")
        clickText("Очередь · 2")
        waitForText("Демо_v3.wav")
        snapshot("33_music_queue")
        pressBack()
        waitForText("Сейчас играет")
        pressBack()
        // Музыка в очереди — над навигацией появляется мини-плеер.
        waitForText("Утро_live")
        compose.onNodeWithContentDescription("Открыть плеер").assertExists()
        snapshot("34_mini_player")
    }

    @Test
    fun cleanerFindsDuplicatesAndAnalysesFolders() {
        val root = Environment.getExternalStorageDirectory()
        val photo = kotlin.random.Random(7).nextBytes(400_000)
        File(root, "DCIM/Camera/IMG_2026.jpg").writeBytes(photo)
        val copy = File(root, "Download/IMG_2026 (1).jpg").apply { writeBytes(photo) }
        File(root, "Pictures/Telegram").mkdirs()
        File(root, "Pictures/Telegram/IMG_2026.jpg").writeBytes(photo)
        File(root, "DCIM/.thumbnails").mkdirs()
        File(root, "DCIM/.thumbnails/1.jpg").writeBytes(ByteArray(80_000))
        File(root, "Download/Старый отчёт.pdf").apply {
            writeBytes(ByteArray(120_000))
            setLastModified(System.currentTimeMillis() - 200L * 24 * 60 * 60 * 1000)
        }
        grantAllFilesAccess()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText("Внутренняя память")
        // Главная показывает сумму безопасных пунктов, как только поиск закончится (F2.3).
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText("Можно освободить", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        snapshot("40_home_can_free")

        clickText("Инструменты")
        waitForText("Рекомендации")
        waitForText("Дубликаты")
        snapshot("41_cleaner")

        clickText("Дубликаты")
        waitForText("Оригинал")
        snapshot("42_cleanup_duplicates")
        compose.onNode(hasText("В корзину", substring = true)).performClick()
        compose.waitUntil(TIMEOUT_MS) { !copy.exists() }
        check(File(root, "DCIM/Camera/IMG_2026.jpg").exists()) { "original must stay" }
        waitForText("Рекомендации")

        clickText("Анализ папок")
        clickText("Память")
        waitForText("DCIM")
        snapshot("43_folder_analysis")
    }

    @Test
    fun batchRenameFromSelection() {
        grantAllFilesAccess()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        clickText("Обзор")
        clickText("Внутренняя память")
        clickText("Download")
        waitForText("photo_2026.jpg")
        compose.onAllNodesWithText("photo_2026.jpg").onFirst().performTouchInput { longClick() }
        clickText("Отчёт за квартал.pdf")
        // Переименование — в «Ещё» панели выбора (F4.3).
        clickText("Ещё")
        clickText("Переименовать")
        waitForText("Переименовать 2 объекта")
        compose.onNode(hasSetTextAction() and hasText("Шаблон имени")).performTextReplacement("Квартал {n}")
        compose.onNode(hasSetTextAction() and hasText("Цифр")).performTextReplacement("2")
        waitForText("Квартал 02.pdf")
        snapshot("44_batch_rename")
        clickText("Переименовать 2")
        val dir = File(Environment.getExternalStorageDirectory(), "Download")
        compose.waitUntil(TIMEOUT_MS) { File(dir, "Квартал 01.jpg").exists() && File(dir, "Квартал 02.pdf").exists() }
        waitForText("Квартал 02.pdf")
    }

    private fun copyResource(name: String, target: File) {
        target.parentFile!!.mkdirs()
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
    }

    private fun findEditor(view: android.view.View): io.quietfiles.feature.editor.ui.CodeEditText {
        if (view is io.quietfiles.feature.editor.ui.CodeEditText) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
            runCatching { return findEditor(view.getChildAt(i)) }
        }
        error("editor not found")
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

    /**
     * Coil декодирует картинки на фоновых потоках, а результат доставляет через главный лупер.
     * Тест сам работает на главном потоке, поэтому просто спать нельзя — ждём, прокручивая лупер
     * и двигая его часы: по ним идёт и плавное появление кадра.
     */
    private fun waitForImages() {
        repeat(IMAGE_WAIT_STEPS) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            compose.waitForIdle()
        }
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
            val decor = activity.window.decorView
            bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { b ->
                val canvas = Canvas(b)
                // Все окна приложения по порядку: само окно, затем диалоги и нижние листы поверх.
                rootViews().ifEmpty { listOf(decor) }.forEach { root ->
                    val location = IntArray(2).also(root::getLocationOnScreen)
                    canvas.save()
                    canvas.translate(location[0].toFloat(), location[1].toFloat())
                    root.draw(canvas)
                    canvas.restore()
                }
            }
        }
        val dir = File(System.getProperty("user.dir"), "build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Корневые окна процесса через скрытый WindowManagerGlobal — так на снимок попадают диалоги. */
    private fun rootViews(): List<android.view.View> = runCatching {
        val cls = Class.forName("android.view.WindowManagerGlobal")
        val global = cls.getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val names = cls.getMethod("getViewRootNames").invoke(global) as Array<String>
        names.mapNotNull { cls.getMethod("getRootView", String::class.java).invoke(global, it) as? android.view.View }
            .filter { it.isShown && it.width > 0 }
    }.getOrDefault(emptyList())

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val IMAGE_WAIT_STEPS = 30
        const val CONFIG_YAML = """# Домашний сервер
server:
  host: 192.168.1.50
  port: 8080
  tls: true

storage:
  path: "/mnt/nas/media"
  max_size: 2TB
  # TODO: включить сжатие
  compress: false

users:
  - name: "ivan"
    role: admin
  - name: "guest"
    role: read-only
"""
        val PALETTE = listOf(0xFF4E8C7A.toInt(), 0xFFE0A458.toInt(), 0xFF5B7DB1.toInt(), 0xFFC1666B.toInt(), 0xFF8E7DBE.toInt())
    }
}
