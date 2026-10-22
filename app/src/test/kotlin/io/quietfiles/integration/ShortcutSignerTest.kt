package io.quietfiles.integration

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShortcutSignerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun acceptsOwnSignatureOnly() {
        val signer = ShortcutSigner(File(tmp.root, "key"))
        val sig = signer.sign("/storage/emulated/0/Документы")

        assertThat(signer.verify("/storage/emulated/0/Документы", sig)).isTrue()
        assertThat(signer.verify("/storage/emulated/0/Android", sig)).isFalse()
        assertThat(signer.verify("/storage/emulated/0/Документы", null)).isFalse()
        assertThat(signer.verify("/storage/emulated/0/Документы", "forged")).isFalse()
    }

    @Test
    fun keyPersistsAndDiffersBetweenInstalls() {
        val path = "/storage/emulated/0/Download"
        val first = ShortcutSigner(File(tmp.root, "a/key"))
        val sig = first.sign(path)
        assertThat(ShortcutSigner(File(tmp.root, "a/key")).verify(path, sig)).isTrue()
        assertThat(ShortcutSigner(File(tmp.root, "b/key")).verify(path, sig)).isFalse()
    }
}
