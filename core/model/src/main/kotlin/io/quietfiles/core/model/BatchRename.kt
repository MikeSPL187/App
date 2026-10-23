package io.quietfiles.core.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class NameCase { KEEP, LOWER, UPPER, TITLE }

/**
 * Правила группового переименования (F5.1). Шаблон собирает новое имя из меток:
 * `{name}` — прежнее имя без расширения, `{n}` — номер, `{date}` — дата изменения (ГГГГ-ММ-ДД).
 * Расширение сохраняется, если не выбрано иное. Затем применяются замена и регистр.
 */
data class RenameRules(
    val template: String = "{name}",
    val find: String = "",
    val replace: String = "",
    val regex: Boolean = false,
    val matchCase: Boolean = false,
    val case: NameCase = NameCase.KEEP,
    val start: Int = 1,
    val step: Int = 1,
    /** Сколько цифр в номере: 3 → «007». 0 — без дополнения нулями. */
    val digits: Int = 0,
    val keepExtension: Boolean = true,
)

/** Строка предпросмотра: что станет с файлом и можно ли так назвать. */
data class RenamePreview(val path: String, val oldName: String, val newName: String, val problem: RenameProblem?) {
    val changed: Boolean get() = oldName != newName
}

enum class RenameProblem { EMPTY, INVALID, DUPLICATE, EXISTS, BAD_PATTERN }

object BatchRename {

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * Предпросмотр для [entries] в заданном порядке. [existing] — имена остальных файлов папки:
     * совпадение с ними — конфликт, а с переименуемыми — нет, их имена освободятся.
     */
    fun preview(entries: List<FileEntry>, rules: RenameRules, existing: Set<String>, zone: ZoneId = ZoneId.systemDefault()): List<RenamePreview> {
        val pattern = if (rules.regex && rules.find.isNotEmpty()) {
            runCatching { Regex(rules.find, if (rules.matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)) }.getOrNull()
                ?: return entries.map { RenamePreview(it.path, it.name, it.name, RenameProblem.BAD_PATTERN) }
        } else {
            null
        }
        val renamedNames = entries.map { it.name.lowercase() }.toSet()
        val raw = entries.mapIndexed { i, entry -> entry to newName(entry, i, rules, pattern, zone) }
        val counts = raw.groupingBy { it.second.lowercase() }.eachCount()
        return raw.map { (entry, name) ->
            val problem = when {
                name.isBlank() -> RenameProblem.EMPTY
                FileNames.validate(name) != FileNames.Validation.Ok -> RenameProblem.INVALID
                (counts[name.lowercase()] ?: 0) > 1 -> RenameProblem.DUPLICATE
                name.lowercase() != entry.name.lowercase() && name.lowercase() in existing.map { it.lowercase() } &&
                    name.lowercase() !in renamedNames -> RenameProblem.EXISTS
                else -> null
            }
            RenamePreview(entry.path, entry.name, name, problem)
        }
    }

    fun newName(entry: FileEntry, index: Int, rules: RenameRules, pattern: Regex? = null, zone: ZoneId = ZoneId.systemDefault()): String {
        val ext = if (entry.isDirectory) "" else FileNames.extension(entry.name)
        val base = if (ext.isEmpty()) entry.name else entry.name.dropLast(ext.length + 1)
        val number = rules.start + index * rules.step
        val numberText = if (rules.digits > 0) number.toString().padStart(rules.digits, '0') else number.toString()
        var name = rules.template.ifEmpty { "{name}" }
            .replace("{name}", base)
            .replace("{n}", numberText)
            .replace("{date}", DATE.format(Instant.ofEpochMilli(entry.lastModified).atZone(zone)))
        if (rules.find.isNotEmpty()) {
            name = when {
                pattern != null -> pattern.replace(name, rules.replace)
                else -> name.replace(rules.find, rules.replace, ignoreCase = !rules.matchCase)
            }
        }
        name = when (rules.case) {
            NameCase.KEEP -> name
            NameCase.LOWER -> name.lowercase()
            NameCase.UPPER -> name.uppercase()
            NameCase.TITLE -> name.split(' ').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.titlecase() } }
        }
        name = name.trim()
        return if (rules.keepExtension && ext.isNotEmpty()) "$name.${if (rules.case == NameCase.KEEP) ext else applyCase(ext, rules.case)}" else name
    }

    private fun applyCase(ext: String, case: NameCase) = when (case) {
        NameCase.UPPER -> ext.uppercase()
        else -> ext.lowercase()
    }

    /**
     * Порядок переименований без столкновений: сначала всё во временные имена, затем в новые.
     * Так обмен «a ↔ b» и сдвиг нумерации не перезаписывают файлы.
     */
    fun plan(previews: List<RenamePreview>, tempSuffix: String = ".qfrename"): List<Pair<String, String>> {
        val moves = previews.filter { it.changed && it.problem == null }
        val parentOf = { p: String -> FileNames.parentOf(p).orEmpty() }
        val first = moves.mapIndexed { i, m -> m.path to FileNames.child(parentOf(m.path), ".${i}_${m.oldName}$tempSuffix") }
        val second = moves.mapIndexed { i, m -> first[i].second to FileNames.child(parentOf(m.path), m.newName) }
        return first + second
    }
}
