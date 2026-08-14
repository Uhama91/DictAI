package com.kafkasl.phonewhisper

/** Android-free allowlist for restarts that restore only the overlay shell. */
internal object OverlayRestartPolicy {
    const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"
    const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"

    data class RestartDescriptor(
        val shouldStart: Boolean,
        val serviceAction: String?,
    )

    fun decide(action: String?): RestartDescriptor = when (action) {
        ACTION_BOOT_COMPLETED,
        ACTION_MY_PACKAGE_REPLACED,
        ACTION_QUICKBOOT_POWERON -> RestartDescriptor(shouldStart = true, serviceAction = null)
        else -> RestartDescriptor(shouldStart = false, serviceAction = null)
    }
}
