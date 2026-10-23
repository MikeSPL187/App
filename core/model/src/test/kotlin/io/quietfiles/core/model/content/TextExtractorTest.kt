package io.quietfiles.core.model.content

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TextExtractorTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Минимальный PDF из объектов; xref разборщику не нужен. */
    private class PdfBuilder {
        private val out = ByteArrayOutputStream().apply { write("%PDF-1.7\n".toByteArray()) }
        fun obj(num: Int, dict: String, stream: ByteArray? = null): PdfBuilder {
            out.write("$num 0 obj\n$dict".toByteArray(Charsets.ISO_8859_1))
            if (stream != null) {
                out.write("\nstream\n".toByteArray())
                out.write(stream)
                out.write("\nendstream".toByteArray())
            }
            out.write("\nendobj\n".toByteArray())
            return this
        }
        fun bytes(): ByteArray = out.toByteArray() + "%%EOF".toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater()
        d.setInput(data)
        d.finish()
        val buf = ByteArray(data.size + 64)
        val n = d.deflate(buf)
        d.end()
        return buf.copyOf(n)
    }

    @Test
    fun simplePdfWithStandardFont() {
        val content = "BT /F1 12 Tf 72 700 Td (Hello PDF) Tj 0 -14 Td [(wor) -20 (ld) -500 (again)] TJ ET".toByteArray()
        val pdf = PdfBuilder()
            .obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
            .obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
            .obj(3, "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>")
            .obj(4, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
            .obj(5, "<< /Length ${content.size} >>", content)
            .bytes()
        val text = PdfText.extract(pdf)
        assertThat(text).contains("Hello PDF")
        assertThat(text).contains("world again")
    }

    @Test
    fun compressedPdfWithToUnicodeInObjectStream() {
        // Шрифт с двухбайтовыми кодами: 0001 → «П», 0002–0006 → «ривет» (диапазон), 0007 → пробел.
        val cmap = """
            /CIDInit /ProcSet findresource begin 12 dict begin begincmap
            1 begincodespacerange <0000> <FFFF> endcodespacerange
            2 beginbfchar <0001> <041F> <0007> <0020> endbfchar
            1 beginbfrange <0002> <0002> <0440> endbfrange
            1 beginbfrange <0003> <0006> [<0438> <0432> <0435> <0442>] endbfrange
            endcmap
        """.trimIndent().toByteArray()
        val content = deflate("BT /F2 11 Tf <000100020003000400050006> Tj ET".toByteArray())
        val fontDict = "<< /Type /Font /Subtype /Type0 /BaseFont /Roboto /ToUnicode 7 0 R >>"
        val header = "6 0 "
        val objStm = (header + fontDict).toByteArray()
        val pdf = PdfBuilder()
            .obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
            .obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
            .obj(3, "<< /Type /Page /Parent 2 0 R /Resources 4 0 R /Contents [5 0 R] >>")
            .obj(4, "<< /Font << /F2 6 0 R >> >>")
            .obj(5, "<< /Length ${content.size} /Filter /FlateDecode >>", content)
            .obj(7, "<< /Length ${cmap.size} >>", cmap)
            .obj(8, "<< /Type /ObjStm /N 1 /First ${header.length} /Length ${deflate(objStm).size} /Filter /FlateDecode >>", deflate(objStm))
            .bytes()
        assertThat(PdfText.extract(pdf)).isEqualTo("Привет")
    }

    @Test
    fun encryptedOrNotPdf() {
        assertThat(PdfText.extract("not a pdf".toByteArray())).isEmpty()
        val pdf = PdfBuilder().obj(1, "<< /Type /Catalog >>").obj(2, "<< /Encrypt 3 0 R >>").bytes()
        assertThat(PdfText.extract(pdf)).isEmpty()
    }

    @Test
    fun docxParagraphsAndEntities() {
        val xml = """<?xml version="1.0"?><w:document><w:body>
            <w:p><w:r><w:t>Договор №5</w:t></w:r><w:r><w:t xml:space="preserve"> аренды</w:t></w:r></w:p>
            <w:p><w:r><w:t>Сумма &amp; сроки</w:t></w:r><w:r><w:tab/><w:t>2026</w:t></w:r></w:p>
            </w:body></w:document>"""
        val file = File(tmp.root, "d.docx")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(xml.toByteArray())
            zip.closeEntry()
        }
        assertThat(TextExtractor.extract(file)).isEqualTo("Договор №5 аренды\nСумма & сроки 2026")
    }

    @Test
    fun plainTextInWindows1251() {
        val file = File(tmp.root, "заметка.txt").apply { writeBytes("Привет, мир! Отчёт за квартал".toByteArray(charset("windows-1251"))) }
        assertThat(TextExtractor.extract(file)).isEqualTo("Привет, мир! Отчёт за квартал")
    }

    @Test
    fun indexableTypesAndSizes() {
        assertThat(TextExtractor.isIndexable("a.TXT", 10)).isTrue()
        assertThat(TextExtractor.isIndexable("a.pdf", 1_000_000)).isTrue()
        assertThat(TextExtractor.isIndexable("a.txt", TextExtractor.MAX_TEXT_BYTES + 1)).isFalse()
        assertThat(TextExtractor.isIndexable("a.jpg", 10)).isFalse()
        assertThat(TextExtractor.normalize("Ёжик и ёлка")).isEqualTo("Ежик и елка")
    }
}
