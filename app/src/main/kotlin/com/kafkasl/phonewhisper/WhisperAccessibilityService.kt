package com.kafkasl.phonewhisper

import android.accessibilityservice.AccessibilityService
import com.kafkasl.phonewhisper.BuildConfig
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WhisperAccessibilityService : AccessibilityService(), InjectionController {

    companion object {
        @Volatile var controller: InjectionController? = null
        private const val TAG = "WhisperPin"
    }

    override fun onServiceConnected() {
        controller = this
        try {
            startForegroundService(Intent(this, OverlayService::class.java))
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "overlay start from accessibility refused: ${e.javaClass.simpleName}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        controller = null
        super.onDestroy()
    }

    override fun inject(text: String): InjectionResult {
        val candidates = findInjectionCandidates()
        return try {
            val fallbackTarget = candidates.firstOrNull(::isKnownFallbackTarget)
            orchestrateInjection(
                directInsert = { candidates.any { tryDirectSetText(it, text) } },
                targetSafety = { targetSafety(fallbackTarget) },
                prepareClipboard = { SensitiveClipboard.copy(this, text) },
                paste = { fallbackTarget?.let(::tryPaste) == true },
            )
        } finally {
            candidates.forEach { it.recycle() }
        }
    }

    override fun isActiveTargetSensitive(): Boolean {
        val candidates = findInjectionCandidates()
        return try {
            targetSafety(candidates.firstOrNull(::isKnownFallbackTarget)) != InjectionTargetSafety.Safe
        } finally {
            candidates.forEach { it.recycle() }
        }
    }

    // --- Injection target discovery ---

    private fun findInjectionCandidates(): List<AccessibilityNodeInfo> {
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        rootInActiveWindow?.let { root ->
            if (BuildConfig.DEBUG) Log.i(TAG, "Active root: package=${root.packageName} class=${root.className}")
            collectInjectionCandidates(root, candidates)
            root.recycle()
        }

        windows
            ?.filter { it.isActive || it.isFocused }
            ?.forEach { window ->
                val root = window.root ?: return@forEach
                if (BuildConfig.DEBUG) Log.i(
                    TAG,
                    "Window root: type=${window.type} active=${window.isActive} focused=${window.isFocused} package=${root.packageName} class=${root.className}"
                )
                collectInjectionCandidates(root, candidates)
                root.recycle()
            }

        return candidates.sortedByDescending(::candidateScore)
    }

    private fun collectInjectionCandidates(
        root: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { out += it }
        root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { out += it }
        collectPotentialTargets(root, out)
    }

    private fun collectPotentialTargets(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (isPotentialInjectionTarget(node)) {
            out += AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectPotentialTargets(child, out)
            } finally {
                child.recycle()
            }
        }
    }

    private fun isPotentialInjectionTarget(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return node.isFocused ||
            node.isEditable ||
            className.contains("EditText") ||
            className.contains("TerminalView") ||
            findCustomPasteAction(node) != null
    }

    private fun candidateScore(node: AccessibilityNodeInfo): Int {
        val className = node.className?.toString().orEmpty()
        var score = 0
        if (findCustomPasteAction(node) != null) score += 100
        if (className.contains("TerminalView")) score += 80
        if (node.isEditable) score += 60
        if (node.isFocused) score += 40
        if (className.contains("EditText")) score += 20
        return score
    }

    private fun isKnownFallbackTarget(node: AccessibilityNodeInfo): Boolean {
        if (!node.isFocused) return false
        val className = node.className?.toString().orEmpty()
        return node.isEditable ||
            className.contains("EditText") ||
            className.contains("TerminalView") ||
            findCustomPasteAction(node) != null ||
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_PASTE }
    }

    private fun targetSafety(node: AccessibilityNodeInfo?): InjectionTargetSafety = when {
        node == null -> InjectionTargetSafety.Unknown
        SensitiveInputPolicy.isSensitive(node.isPassword, node.inputType) ->
            InjectionTargetSafety.Sensitive
        else -> InjectionTargetSafety.Safe
    }

    private fun tryDirectSetText(node: AccessibilityNodeInfo, text: String): Boolean {
        val updated = readAfterSuccessfulRefresh(
            refresh = { node.refresh() },
            read = read@{
                if (!node.isFocused || !node.isEditable) return@read null
                if (SensitiveInputPolicy.isSensitive(node.isPassword, node.inputType)) return@read null
                composeDirectSetText(
                    currentText = node.text,
                    selectionStart = node.textSelectionStart,
                    selectionEnd = node.textSelectionEnd,
                    dictatedText = text,
                )
            },
        ) ?: return false

        logNode("Trying direct node", node)
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                updated,
            )
        }
        val setTextOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (BuildConfig.DEBUG) Log.i(TAG, "ACTION_SET_TEXT => $setTextOk")
        return setTextOk
    }

    private fun tryPaste(node: AccessibilityNodeInfo): Boolean {
        logNode("Trying paste node", node)
        findCustomPasteAction(node)?.let { action ->
            val ok = node.performAction(action.id)
            if (BuildConfig.DEBUG) Log.i(TAG, "Custom action (${action.id}) => $ok")
            if (ok) return true
        }

        val pasteOk = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (BuildConfig.DEBUG) Log.i(TAG, "ACTION_PASTE => $pasteOk")
        return pasteOk
    }

    private fun findCustomPasteAction(node: AccessibilityNodeInfo): AccessibilityNodeInfo.AccessibilityAction? =
        node.actionList.firstOrNull { action ->
            action.label?.toString()?.contains("paste", ignoreCase = true) == true
        }

    private fun logNode(prefix: String, node: AccessibilityNodeInfo) {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "$prefix package=${node.packageName} class=${node.className} focused=${node.isFocused} editable=${node.isEditable}")
    }

    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)
}
