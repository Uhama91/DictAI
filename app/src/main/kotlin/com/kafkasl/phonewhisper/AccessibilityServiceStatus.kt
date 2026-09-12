package com.kafkasl.phonewhisper

/**
 * Separates the Android setting from the process connection. The service can be enabled in
 * Android while its process is restarting, so that state should not be reported as disabled.
 */
internal enum class AccessibilityServiceStatus(val subtitle: String) {
    CONNECTED("Activé"),
    ENABLED_NOT_CONNECTED("Activé dans Android · connexion indisponible"),
    DISABLED("À activer dans les réglages");

    companion object {
        fun resolve(enabledInAndroid: Boolean, connected: Boolean): AccessibilityServiceStatus = when {
            connected -> CONNECTED
            enabledInAndroid -> ENABLED_NOT_CONNECTED
            else -> DISABLED
        }
    }
}
