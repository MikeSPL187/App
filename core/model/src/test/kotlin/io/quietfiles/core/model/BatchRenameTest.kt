package io.quietfiles.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class BatchRenameTest {

    private val day = LocalDate.of(2026, 10, 21).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun file(name: String) = FileEntry("/d/$name", name, false, 1, day)

    private fun names(entries: List<FileEntry>, rules: RenameRules, existing: Set<String> = emptySet()) =
        BatchRename.preview(entries, rules, existing, ZoneOffset.UTC).map { it.newName }

    @Test
    fun templateWithNumberingAndDate() {
        val files = listOf(file("IMG_1.jpg"), file("IMG_2.JPG"), file("notes"))
        assertThat(names(files, RenameRules(template = "Отпуск {n}", digits = 3)))
            .containsExactly("Отпуск 001.jpg", "Отпуск 002.JPG", "Отпуск 003").inOrder()
        assertThat(names(files, RenameRules(template = "{date}_{name}", start = 10, step = 5)))
            .containsExactly("2026-10-21_IMG_1.jpg", "2026-10-21_IMG_2.JPG", "2026-10-21_notes").inOrder()
        assertThat(names(files.take(2), RenameRules(template = "{n}", start = 10, step = 5))).containsExactly("10.jpg", "15.JPG").inOrder()
    }

    @Test
    fun findReplaceCaseAndRegex() {
        val files = listOf(file("Photo_2026_draft.png"), file("photo_old_DRAFT.png"))
        assertThat(names(files, RenameRules(find = "draft", replace = "final")))
            .containsExactly("Photo_2026_final.png", "photo_old_final.png").inOrder()
        assertThat(names(files, RenameRules(find = "draft", replace = "final", matchCase = true)))
            .containsExactly("Photo_2026_final.png", "photo_old_DRAFT.png").inOrder()
        assertThat(names(files, RenameRules(find = "_(\\d+)_", replace = "-$1-", regex = true)).first()).isEqualTo("Photo-2026-draft.png")
        assertThat(names(files, RenameRules(case = NameCase.UPPER)).first()).isEqualTo("PHOTO_2026_DRAFT.PNG")
        assertThat(names(listOf(file("мой отпуск.TXT")), RenameRules(case = NameCase.TITLE))).containsExactly("Мой Отпуск.txt")
    }

    @Test
    fun problemsAreReported() {
        val files = listOf(file("a.txt"), file("b.txt"))
        val dup = BatchRename.preview(files, RenameRules(template = "same"), emptySet())
        assertThat(dup.map { it.problem }).containsExactly(RenameProblem.DUPLICATE, RenameProblem.DUPLICATE)
        val exists = BatchRename.preview(files.take(1), RenameRules(template = "c"), setOf("c.txt", "a.txt"))
        assertThat(exists.single().problem).isEqualTo(RenameProblem.EXISTS)
        val invalid = BatchRename.preview(files.take(1), RenameRules(template = "a/b"), emptySet())
        assertThat(invalid.single().problem).isEqualTo(RenameProblem.INVALID)
        val badRegex = BatchRename.preview(files, RenameRules(find = "(", regex = true), emptySet())
        assertThat(badRegex.map { it.problem }.toSet()).containsExactly(RenameProblem.BAD_PATTERN)
    }

    @Test
    fun swappingNamesIsAllowedAndPlannedThroughTemporaryNames() {
        val files = listOf(file("a.txt"), file("b.txt"))
        // a → b, b → a: имена освобождаются друг другом, конфликта нет.
        val previews = listOf(
            RenamePreview("/d/a.txt", "a.txt", "b.txt", null),
            RenamePreview("/d/b.txt", "b.txt", "a.txt", null),
        )
        val plan = BatchRename.plan(previews)
        assertThat(plan).hasSize(4)
        assertThat(plan.take(2).map { it.first }).containsExactly("/d/a.txt", "/d/b.txt")
        assertThat(plan.drop(2).map { it.second }).containsExactly("/d/b.txt", "/d/a.txt")
        assertThat(BatchRename.preview(files, RenameRules(template = "{name}"), setOf("a.txt", "b.txt")).all { it.problem == null }).isTrue()
    }
}
