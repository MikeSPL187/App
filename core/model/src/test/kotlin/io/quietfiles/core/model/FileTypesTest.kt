package io.quietfiles.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FileTypesTest {

    @Test
    fun mimeFilters() {
        assertThat(FileTypes.mimeMatches("image/png", "image/*")).isTrue()
        assertThat(FileTypes.mimeMatches("IMAGE/PNG", "image/png")).isTrue()
        assertThat(FileTypes.mimeMatches("video/mp4", "image/*")).isFalse()
        assertThat(FileTypes.mimeMatches("application/pdf", "*/*")).isTrue()
        assertThat(FileTypes.mimeMatches("text/plain", "text/plain; charset=utf-8")).isTrue()
        assertThat(FileTypes.mimeMatchesAny("application/zip", emptyList())).isTrue()
        assertThat(FileTypes.mimeMatchesAny("application/zip", listOf("image/*", "application/pdf"))).isFalse()
    }

    @Test
    fun extensionForMime() {
        assertThat(FileTypes.extensionForMime("image/jpeg")).isEqualTo("jpg")
        assertThat(FileTypes.extensionForMime("application/pdf")).isEqualTo("pdf")
        assertThat(FileTypes.extensionForMime("text/plain; charset=utf-8")).isEqualTo("txt")
        assertThat(FileTypes.extensionForMime("application/x-unknown")).isNull()
    }
}
