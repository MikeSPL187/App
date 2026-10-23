package io.quietfiles.core.operations

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Пароли архивов (F5.11) — только в памяти процесса, никогда на диске: журнал операций хранит
 * запрос без пароля, а после перезапуска пароль спросят снова. Пароль сжатия затирается сразу
 * после операции; пароль распаковки живёт, пока открыт процесс, чтобы не спрашивать его на каждый
 * файл архива, и забывается, если оказался неверным.
 */
@Singleton
class ArchivePasswords @Inject constructor() {
    private val passwords = HashMap<String, CharArray>()

    @Synchronized
    fun put(path: String, password: CharArray) {
        remove(path)
        passwords[path] = password.copyOf()
    }

    /** Копия пароля: вызывающий может её затереть, не трогая хранимый. */
    @Synchronized
    fun get(path: String): CharArray? = passwords[path]?.copyOf()

    @Synchronized
    fun remove(path: String) {
        passwords.remove(path)?.fill('\u0000')
    }
}
