package io.quietfiles.feature.editor.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SyntaxTest {

    private fun lex(lexer: Lexer, line: String, state: Int = Lexer.INITIAL): Pair<List<Pair<String, TokenType>>, Int> {
        val out = mutableListOf<Token>()
        val next = lexer.lex(line, state, out)
        return out.map { line.substring(it.start, it.end) to it.type } to next
    }

    @Test
    fun languageByName() {
        assertThat(Language.of("config.YAML")).isEqualTo(Language.YAML)
        assertThat(Language.of("app.properties")).isEqualTo(Language.INI)
        assertThat(Language.of(".bashrc")).isEqualTo(Language.SHELL)
        assertThat(Language.of("readme.md")).isEqualTo(Language.MARKDOWN)
        assertThat(Language.of("notes.txt")).isEqualTo(Language.PLAIN)
    }

    @Test
    fun yaml() {
        val (tokens, _) = lex(YamlLexer, "  - name: \"ivan\" # admin")
        assertThat(tokens).containsExactly("name" to TokenType.KEY, "\"ivan\"" to TokenType.STRING, "# admin" to TokenType.COMMENT).inOrder()
        assertThat(lex(YamlLexer, "port: 8080").first).contains("8080" to TokenType.NUMBER)
        assertThat(lex(YamlLexer, "role: read-only").first).contains("read-only" to TokenType.STRING)
        assertThat(lex(YamlLexer, "url: http://x:80/a").first).contains("url" to TokenType.KEY)
    }

    @Test
    fun json() {
        val (tokens, _) = lex(JsonLexer, """{"port": 8080, "host": "nas", "tls": true}""")
        assertThat(tokens).containsExactly(
            "\"port\"" to TokenType.KEY, "8080" to TokenType.NUMBER, "\"host\"" to TokenType.KEY,
            "\"nas\"" to TokenType.STRING, "\"tls\"" to TokenType.KEY, "true" to TokenType.NUMBER,
        ).inOrder()
    }

    @Test
    fun xmlCommentSpansLines() {
        val (first, state) = lex(XmlLexer, "<a href=\"x\"><!-- note")
        assertThat(first).containsAtLeast("<a" to TokenType.TAG, "href" to TokenType.ATTRIBUTE, "\"x\"" to TokenType.STRING)
        val (second, end) = lex(XmlLexer, "still --> <b/>", state)
        assertThat(second.first()).isEqualTo("still -->" to TokenType.COMMENT)
        assertThat(second).contains("<b" to TokenType.TAG)
        assertThat(end).isEqualTo(Lexer.INITIAL)
    }

    @Test
    fun markdownFenceAndInline() {
        val (_, inFence) = lex(MarkdownLexer, "```kotlin")
        assertThat(lex(MarkdownLexer, "val x = 1", inFence).first).containsExactly("val x = 1" to TokenType.CODE)
        assertThat(lex(MarkdownLexer, "## Заголовок").first).containsExactly("## Заголовок" to TokenType.HEADING)
        assertThat(lex(MarkdownLexer, "- **важно** и `код`").first)
            .containsExactly("-" to TokenType.KEY, "**важно**" to TokenType.EMPHASIS, "`код`" to TokenType.CODE).inOrder()
    }

    @Test
    fun shellAndIniAndLog() {
        assertThat(lex(ShellLexer, "if [ \"\$HOME\" ]; then echo \${PATH} # done").first).containsAtLeast(
            "if" to TokenType.KEY, "\"\$HOME\"" to TokenType.STRING, "\${PATH}" to TokenType.VARIABLE, "# done" to TokenType.COMMENT,
        )
        assertThat(lex(IniLexer, "[server]").first).containsExactly("[server]" to TokenType.TAG)
        assertThat(lex(IniLexer, "port = 8080").first).containsExactly("port" to TokenType.KEY, "8080" to TokenType.NUMBER)
        val log = lex(LogLexer, "2026-10-23 06:10:01.123 E/Camera: failed to open").first
        assertThat(log.first()).isEqualTo("2026-10-23 06:10:01.123" to TokenType.MUTED)
        assertThat(log).contains("E" to TokenType.ERROR)
        assertThat(lex(LogLexer, "12:00:01 INFO started").first).contains("INFO" to TokenType.KEY)
    }
}
