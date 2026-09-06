package com.kafkasl.phonewhisper

import android.text.InputType

/** Contrat exposé par le service d'accessibilité au OverlayService. */
interface InjectionController {
    fun inject(text: String): InjectionResult

    /** A legacy or unavailable controller must conservatively suppress cloud cleanup. */
    fun isActiveTargetSensitive(): Boolean = true
}

enum class InjectionResult {
    Inserted,
    Copied,
    Failed,
}

enum class InjectionTargetSafety {
    Safe,
    Sensitive,
    Unknown,
}

internal fun orchestrateInjection(
    directInsert: () -> Boolean,
    targetSafety: () -> InjectionTargetSafety,
    prepareClipboard: () -> Boolean,
    paste: () -> Boolean,
): InjectionResult {
    if (directInsert()) return InjectionResult.Inserted
    if (targetSafety() != InjectionTargetSafety.Safe) return InjectionResult.Failed
    if (!prepareClipboard()) return InjectionResult.Failed
    return if (paste()) InjectionResult.Inserted else InjectionResult.Copied
}

internal fun injectionFeedbackMessage(result: InjectionResult): String? = when (result) {
    InjectionResult.Inserted -> null
    InjectionResult.Copied -> "Insertion impossible — texte copié"
    InjectionResult.Failed -> "Insertion impossible"
}

internal fun injectOrCopy(
    controller: InjectionController?,
    text: String,
    copyToClipboard: (String) -> Boolean,
): InjectionResult {
    val copied = runCatching { copyToClipboard(text) }.getOrDefault(false)
    val insertion = runCatching { controller?.inject(text) }.getOrNull()
    return when {
        insertion == InjectionResult.Inserted -> InjectionResult.Inserted
        copied || insertion == InjectionResult.Copied -> InjectionResult.Copied
        else -> InjectionResult.Failed
    }
}

internal fun composeDirectSetText(
    currentText: CharSequence?,
    selectionStart: Int,
    selectionEnd: Int,
    dictatedText: String,
): String? {
    if (currentText == null) {
        return dictatedText.takeIf { selectionStart == 0 && selectionEnd == 0 }
    }

    val current = currentText.toString()
    if (selectionStart !in 0..current.length || selectionEnd !in 0..current.length) return null
    return current.replaceRange(
        minOf(selectionStart, selectionEnd),
        maxOf(selectionStart, selectionEnd),
        dictatedText,
    )
}

internal fun <T> focusAndReadFresh(
    initiallyFocused: Boolean,
    requestFocus: () -> Boolean,
    refresh: () -> Boolean,
    readFresh: () -> T?,
): T? {
    if (!initiallyFocused && !requestFocus()) return null
    if (!refresh()) return null
    return readFresh()
}

internal fun <T> selectInjectionTarget(
    candidates: List<T>,
    isFocused: (T) -> Boolean,
    isKnownEditable: (T) -> Boolean,
    isKnownFallback: (T) -> Boolean,
): T? = candidates.firstOrNull { candidate ->
    isFocused(candidate) && isKnownEditable(candidate)
} ?: candidates.firstOrNull { candidate ->
    isFocused(candidate)
} ?: candidates.firstOrNull { candidate ->
    isKnownFallback(candidate)
}

internal fun injectionCandidateScore(
    isFocused: Boolean,
    isEditable: Boolean,
    isEditText: Boolean,
    isTerminalView: Boolean,
    hasCustomPasteAction: Boolean,
): Int {
    var score = when {
        isFocused && isEditable -> 3_000
        isFocused -> 2_000
        isEditable || isEditText || isTerminalView || hasCustomPasteAction -> 1_000
        else -> 0
    }
    if (isFocused) score += 1_000
    if (hasCustomPasteAction) score += 100
    if (isTerminalView) score += 80
    if (isEditable) score += 60
    if (isEditText) score += 20
    return score
}

internal fun freshFallbackTargetSafety(
    isKnownFallbackTarget: Boolean,
    isPassword: Boolean,
    inputType: Int,
): InjectionTargetSafety = when {
    !isKnownFallbackTarget -> InjectionTargetSafety.Unknown
    SensitiveInputPolicy.isSensitive(isPassword, inputType) -> InjectionTargetSafety.Sensitive
    else -> InjectionTargetSafety.Safe
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
