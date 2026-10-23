package io.quietfiles.core.common

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL

/** Проверка пользователя для блокировки приложения: сильная биометрия или PIN, графический ключ, пароль. */
object DeviceAuth {
    /**
     * Можно ли вообще проверить пользователя. Без блокировки экрана на устройстве включённая
     * блокировка приложения не должна запирать файлы навсегда — тогда она не действует.
     */
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
}
