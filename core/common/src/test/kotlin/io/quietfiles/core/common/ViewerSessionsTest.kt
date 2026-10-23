package io.quietfiles.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ViewerSessionsTest {
    @Test
    fun storesListsAndForgetsOldest() {
        val sessions = ViewerSessions()
        assertThat(sessions.put(emptyList())).isEqualTo(0)
        val first = sessions.put(listOf("/a"))
        val ids = (1..8).map { sessions.put(listOf("/p$it")) }
        assertThat(sessions.get(first)).isNull()
        assertThat(sessions.get(ids.last())).containsExactly("/p8")
        assertThat(sessions.get(0)).isNull()
    }
}
