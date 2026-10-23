package io.quietfiles.feature.editor.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TextSearchTest {

    private val text = "name: Иван\nNAME: Пётр\nnickname: Ёж"

    @Test
    fun plainSearchIgnoresCaseIncludingCyrillic() {
        assertThat(TextSearch.find(text, SearchQuery("name")).matches).hasSize(3)
        assertThat(TextSearch.find(text, SearchQuery("name", matchCase = true)).matches).hasSize(2)
        assertThat(TextSearch.find(text, SearchQuery("иван")).matches).containsExactly(6..9)
        assertThat(TextSearch.find(text, SearchQuery("a.c")).matches).isEmpty()
    }

    @Test
    fun regexWithGroupsAndErrors() {
        val q = SearchQuery("^(\\w+): (\\S+)", regex = true)
        assertThat(TextSearch.find(text, q).matches).hasSize(3)
        val (replaced, count) = TextSearch.replaceAll(text, q, "$2 = $1")
        assertThat(count).isEqualTo(3)
        assertThat(replaced.lines().first()).isEqualTo("Иван = name")
        assertThat(TextSearch.find(text, SearchQuery("(", regex = true)).error).isNotNull()
    }

    @Test
    fun replaceSingleMatchUsesGroups() {
        val q = SearchQuery("(\\w+)man", regex = true)
        val s = "superman"
        val m = TextSearch.find(s, q).matches.single()
        assertThat(TextSearch.replacementFor(s, m, q, "$1woman")).isEqualTo("superwoman")
        assertThat(TextSearch.replacementFor(s, m, q.copy(regex = false), "$1")).isEqualTo("$1")
    }

    @Test
    fun plainReplaceKeepsDollarsLiteral() {
        val (r, n) = TextSearch.replaceAll("a.b a.b", SearchQuery("a.b"), "\$x")
        assertThat(n).isEqualTo(2)
        assertThat(r).isEqualTo("\$x \$x")
    }
}
