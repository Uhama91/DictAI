package com.kafkasl.phonewhisper

// Modified from Phone Whisper by kafkasl for DictAI; see repository NOTICE.

import android.accessibilityservice.AccessibilityService
import com.kafkasl.phonewhisper.BuildConfig
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WhisperAccessibilityService : AccessibilityService(), InjectionController {
    internal var lastInsertionIssue: String? = null
        private set
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        clearConnection()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearConnection()
        return super.onUnbind(intent)
    }

    private fun clearConnection() {
        if (connected === this) connected = null
        InjectionGateway.unregister(this)
    }

    override fun inject(text: String): InjectionResult {
        lastInsertionIssue = "Aucun champ accessible. Touchez le champ de l’application avant de dicter."
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
                    if (selectedTarget != null && preparedTarget == null) {
                        lastInsertionIssue = "Le champ a perdu le focus ou n’est plus disponible. Touchez-le à nouveau avant de dicter."
                    }
                    preparedTarget?.let { tryDirectSetText(it, text) } == true
                },
                targetSafety = { targetSafety(preparedTarget) },
                prepareClipboard = { DictationClipboard.copy(this, text) },
                paste = { preparedTarget?.node?.let(::tryPaste) == true },
            ).also { if (it == InjectionResult.Inserted) lastInsertionIssue = null }
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
        lastInsertionIssue = "Ce champ ne permet pas l’écriture directe. Si le collage automatique est refusé, utilisez un appui long → Coller."
        if (!target.isEditable) return false
        if (SensitiveInputPolicy.isSensitive(target.isPassword, target.inputType)) {
            lastInsertionIssue = "Champ protégé : l’insertion automatique est désactivée."
            return false
        }
        val node = target.node
        lastInsertionIssue = "La position du curseur n’est pas fournie par l’application. Touchez le champ ou utilisez un appui long → Coller."
        val updated = composeDirectSetText(
            // Android may expose a placeholder as text with selection -1/-1.
            // It is not user content and must neither block SET_TEXT nor be inserted with it.
            currentText = if (node.isShowingHintText) "" else node.text,
            selectionStart = node.textSelectionStart,
            selectionEnd = node.textSelectionEnd,
            dictatedText = text,
        ) ?: return false

        logNode("Trying direct node", node)
        lastInsertionIssue = "L’application refuse l’écriture dans ce champ. Utilisez un appui long → Coller."
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
