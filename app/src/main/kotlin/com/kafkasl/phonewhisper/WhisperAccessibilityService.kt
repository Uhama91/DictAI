package com.kafkasl.phonewhisper

import android.accessibilityservice.AccessibilityService
import com.kafkasl.phonewhisper.BuildConfig
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WhisperAccessibilityService : AccessibilityService(), InjectionController {
    private var lastExternalEditor: AccessibilityNodeInfo? = null

    internal class ImageTarget internal constructor(internal val node: AccessibilityNodeInfo) : AutoCloseable {
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val packageName: String = node.packageName?.toString().orEmpty()
        override fun close() { if (closed.compareAndSet(false, true)) node.recycle() }
    }

    companion object {
        private const val TAG = "WhisperPin"
        @Volatile internal var connected: WhisperAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        connected = this
        InjectionGateway.register(this)
        try {
            startForegroundService(Intent(this, OverlayService::class.java))
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "overlay start from accessibility refused: ${e.javaClass.simpleName}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED || event.packageName?.toString() == packageName) return
        val node = event.source ?: return
        try {
            if (node.isEditable) {
                lastExternalEditor?.recycle()
                lastExternalEditor = if (SensitiveInputPolicy.isSensitive(node.isPassword, node.inputType)) null
                    else AccessibilityNodeInfo.obtain(node)
            }
        } finally { node.recycle() }
    }
    override fun onInterrupt() {}

    override fun onDestroy() {
        lastExternalEditor?.recycle(); lastExternalEditor = null
        if (connected === this) connected = null
        InjectionGateway.unregister(this)
        super.onDestroy()
    }

    /** Retain the actual editor node at the gesture, not merely the app package or a screen position. */
    internal fun snapshotImageTarget(): ImageTarget? {
        val candidates = findInjectionCandidates()
        try {
            val editors = candidates.filter { it.isEditable && it.isVisibleToUser && !SensitiveInputPolicy.isSensitive(it.isPassword, it.inputType) }.distinct()
            val current = editors.firstOrNull { it.isFocused } ?: editors.singleOrNull()
                ?: lastExternalEditor?.takeIf { it.refresh() && it.isEditable && !SensitiveInputPolicy.isSensitive(it.isPassword, it.inputType) }
            if (current != null && current !== lastExternalEditor) {
                lastExternalEditor?.recycle()
                lastExternalEditor = AccessibilityNodeInfo.obtain(current)
            }
            return current?.let { ImageTarget(AccessibilityNodeInfo.obtain(it)) }
        } finally { candidates.forEach { it.recycle() } }
    }

    internal fun pasteImage(target: ImageTarget, uri: android.net.Uri): ImagePasteResult {
        val node = target.node
        val root = rootInActiveWindow ?: return ImagePasteResult.TARGET_CHANGED
        val currentWindow = try { root.packageName?.toString() == target.packageName && root.windowId == node.windowId }
            finally { root.recycle() }
        if (!node.refresh()) return ImagePasteResult.TARGET_CHANGED
        if (!currentWindow || !node.isEditable || !node.isVisibleToUser || SensitiveInputPolicy.isSensitive(node.isPassword, node.inputType))
            return ImagePasteResult.TARGET_CHANGED
        val prepared = focusAndReadTarget(node) ?: return ImagePasteResult.TARGET_CHANGED
        if (targetSafety(prepared) != InjectionTargetSafety.Safe) return ImagePasteResult.TARGET_CHANGED
        ImagePasteSafety.allow(true, node.isEditable, node.isPassword, node.textSelectionStart, node.textSelectionEnd)?.let { return it }
        if (!NoteImagePaste.copy(this, uri)) return ImagePasteResult.FAILED
        // Refresh after changing the clipboard: native editors can then advertise their paste action.
        return if (tryPaste(node)) ImagePasteResult.REQUESTED else ImagePasteResult.COPIED
    }

    override fun inject(text: String): InjectionResult {
        val candidates = findInjectionCandidates()
        val selectedTarget = selectInjectionTarget(
            candidates = candidates,
            isFocused = { it.isFocused },
            isKnownEditable = { it.isEditable },
            isKnownFallback = ::isKnownFallbackTarget,
        )
        return try {
            var preparedTarget: PreparedTarget? = null
            orchestrateInjection(
                directInsert = {
                    preparedTarget = selectedTarget?.let(::focusAndReadTarget)
                    preparedTarget?.let { tryDirectSetText(it, text) } == true
                },
                targetSafety = { targetSafety(preparedTarget) },
                prepareClipboard = { DictationClipboard.copy(this, text) },
                paste = { preparedTarget?.node?.let(::tryPaste) == true },
            )
        } finally {
            candidates.forEach { it.recycle() }
        }
    }

    override fun isActiveTargetSensitive(): Boolean {
        val candidates = findInjectionCandidates()
        return try {
            val focusedTarget = candidates.firstOrNull { it.isFocused }
            targetSafety(focusedTarget?.let { focusAndReadTarget(it, allowFocus = false) }) !=
                InjectionTargetSafety.Safe
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
        if (root.packageName?.toString() == packageName) return
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
        return injectionCandidateScore(
            isFocused = node.isFocused,
            isEditable = node.isEditable,
            isEditText = className.contains("EditText"),
            isTerminalView = className.contains("TerminalView"),
            hasCustomPasteAction = findCustomPasteAction(node) != null,
        )
    }

    private fun isKnownFallbackTarget(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return node.isEditable ||
            className.contains("EditText") ||
            className.contains("TerminalView") ||
            findCustomPasteAction(node) != null ||
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_PASTE }
    }

    private fun targetSafety(target: PreparedTarget?): InjectionTargetSafety = when {
        target == null -> InjectionTargetSafety.Unknown
        else -> freshFallbackTargetSafety(
            isKnownFallbackTarget = target.isKnownFallbackTarget,
            isPassword = target.isPassword,
            inputType = target.inputType,
        )
    }

    private data class PreparedTarget(
        val node: AccessibilityNodeInfo,
        val isKnownFallbackTarget: Boolean,
        val isEditable: Boolean,
        val isPassword: Boolean,
        val inputType: Int,
    )

    private fun focusAndReadTarget(
        node: AccessibilityNodeInfo,
        allowFocus: Boolean = true,
    ): PreparedTarget? {
        val initiallyFocused = node.isFocused
        if (!allowFocus && !initiallyFocused) return null
        return focusAndReadFresh(
            initiallyFocused = initiallyFocused,
            requestFocus = {
                if (allowFocus) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) else false
            },
            refresh = { node.refresh() },
            readFresh = read@{
                if (!node.isFocused) return@read null
                PreparedTarget(
                    node = node,
                    isKnownFallbackTarget = isKnownFallbackTarget(node),
                    isEditable = node.isEditable,
                    isPassword = node.isPassword,
                    inputType = node.inputType,
                )
            },
        )
    }

    private fun tryDirectSetText(target: PreparedTarget, text: String): Boolean {
        if (!target.isEditable) return false
        if (SensitiveInputPolicy.isSensitive(target.isPassword, target.inputType)) return false
        val node = target.node
        val updated = composeDirectSetText(
            currentText = node.text,
            selectionStart = node.textSelectionStart,
            selectionEnd = node.textSelectionEnd,
            dictatedText = text,
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
        if (!node.refresh()) return false
        if (!node.isFocused || !isKnownFallbackTarget(node)) return false
        val isPassword = node.isPassword
        val inputType = node.inputType
        if (SensitiveInputPolicy.isSensitive(isPassword, inputType)) return false

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
