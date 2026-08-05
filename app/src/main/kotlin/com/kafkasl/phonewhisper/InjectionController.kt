package com.kafkasl.phonewhisper

import android.text.InputType

/** Contrat exposé par le service d'accessibilité au OverlayService. */
interface InjectionController {
    fun inject(text: String): Boolean

    /** A legacy or unavailable controller must conservatively suppress cloud cleanup. */
    fun isActiveTargetSensitive(): Boolean = true
}

object SensitiveInputPolicy {
    fun isSensitive(isPassword: Boolean, inputType: Int): Boolean {
        if (isPassword) return true
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }
}

object CloudSensitiveTargetPolicy {
    data class Snapshot(
        val cloudAllowed: Boolean,
        val suppressedForSensitiveTarget: Boolean,
    )

    fun snapshot(cloudRequested: Boolean, targetSensitive: Boolean): Snapshot = Snapshot(
        cloudAllowed = cloudRequested && !targetSensitive,
        suppressedForSensitiveTarget = cloudRequested && targetSensitive,
    )
}
