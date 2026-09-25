package com.kafkasl.phonewhisper.meeting

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ReplacementSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.kafkasl.phonewhisper.NoteImage
import com.kafkasl.phonewhisper.OverlayImageBlockSpan
import com.kafkasl.phonewhisper.OverlayTranscriptEditor
import com.kafkasl.phonewhisper.ThemePalette
import com.kafkasl.phonewhisper.ThemeTokens
import com.kafkasl.phonewhisper.TranscriptImageBlockRenderer
import com.kafkasl.phonewhisper.TranscriptImageBlocks
import com.kafkasl.phonewhisper.TranscriptImageCaret
import com.kafkasl.phonewhisper.TranscriptImageProjection

internal data class MeetingPanelEntry(
    val stableKey: String,
    val turnId: String?,
    val row: MeetingProjection.Row?,
    val projection: TranscriptImageProjection,
    val images: List<NoteImage>,
    val orphanImages: Boolean = false,
)

internal interface MeetingPanelRowCallbacks {
    fun edit(turnId: String, serializedText: String)
    fun assign(turnId: String)
    fun acquireWindow()
    fun finishEditing()
    fun imageAction(entry: MeetingPanelEntry, image: NoteImage)
}

/** A recycled speech row that keeps its EditText, selection, and IME spans across revisions. */
internal class MeetingTurnEditor(context: Context) : LinearLayout(context) {
    private val labelView = TextView(context)
    private val bodyHost = FrameLayout(context)
    private val contentColumn = LinearLayout(context).apply { orientation = VERTICAL }
    private val imageActionContent = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val imageActions = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = true
        addView(imageActionContent, ViewGroup.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }
    private val imageRenderer = TranscriptImageBlockRenderer(context)
    private var palette = ThemeTokens.palette(context)
    private var compact = false
    private var editableView: OverlayTranscriptEditor? = null
    private var readOnlyView: TextView? = null
    private var callbacks: MeetingPanelRowCallbacks? = null
    private var suppressTextWatcher = false
    private var notifyingAction = false
    private var lastEmittedText: String? = null

    internal var entry: MeetingPanelEntry? = null
        private set
    internal val isNotifyingEdit: Boolean get() = notifyingAction

    internal fun setCompact(value: Boolean) {
        if (compact == value) return
        compact = value
        orientation = if (compact) HORIZONTAL else VERTICAL
        if (compact) {
            labelView.layoutParams = LayoutParams(dp(48), LayoutParams.MATCH_PARENT)
            contentColumn.layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            labelView.maxLines = 1
            labelView.ellipsize = android.text.TextUtils.TruncateAt.END
        } else {
            labelView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(48))
            contentColumn.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            labelView.maxLines = 2
            labelView.ellipsize = null
        }
        updateSpeakerLabel()
        requestLayout()
    }

    internal fun refreshTheme(palette: ThemePalette) {
        this.palette = palette
        labelView.setTextColor(palette.inkMuted)
        editableView?.apply {
            setTextColor(palette.ink)
            setHintTextColor(palette.inkMuted)
        }
        readOnlyView?.setTextColor(palette.ink)
        for (index in 0 until imageActionContent.childCount) {
            (imageActionContent.getChildAt(index) as? Button)?.let(::styleImageButton)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(dp(8), dp(5), dp(8), dp(5))
        addView(labelView, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        contentColumn.addView(bodyHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        contentColumn.addView(imageActions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(contentColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        labelView.setTextColor(palette.inkMuted)
        labelView.setPadding(dp(8), 0, dp(8), 0)
        labelView.minHeight = dp(48)
        labelView.isFocusable = true
        labelView.isClickable = true
        bodyHost.minimumHeight = dp(48)
    }

    fun bind(newEntry: MeetingPanelEntry, rowCallbacks: MeetingPanelRowCallbacks) {
        entry = newEntry
        callbacks = rowCallbacks
        val row = newEntry.row
        val turnId = newEntry.turnId
        val label = row?.label
        updateSpeakerLabel(label)
        labelView.visibility = if (label == null) View.GONE else View.VISIBLE
        labelView.setOnClickListener {
            val current = entry?.takeIf { it.row?.label != null } ?: return@setOnClickListener
            current.turnId?.let { id -> callbacks?.assign(id) }
        }

        if (row?.editableSpeech == true && turnId != null) {
            bindEditable(turnId, newEntry.projection, row.label)
        } else {
            removeEditableEditor()
            bindReadOnly(newEntry.projection, newEntry.orphanImages)
        }
        bindImageActions(newEntry)
    }

    internal val speechEditor: OverlayTranscriptEditor? get() = editableView

    internal fun flushFocusedEdit(): Boolean {
        val editor = editableView ?: return false
        if (!editor.isFocused) return false
        publishCurrentText(editor)
        return true
    }

    internal fun finishComposition() {
        val editor = editableView ?: return
        (editor.text as? Spannable)?.let(BaseInputConnection::removeComposingSpans)
    }

    internal fun endEditingField() {
        editableView?.endEditing()
    }

    internal fun bindPlaceholder() {
        if (editableView?.hasFocus() == true) return
        entry = null
        callbacks = null
        labelView.visibility = View.GONE
        editableView?.visibility = View.GONE
        readOnlyView?.visibility = View.GONE
        imageActions.visibility = View.GONE
    }

    internal fun serializedAnchor(): MeetingPanelAnchor? {
        val current = entry ?: return null
        val id = current.turnId ?: return null
        val row = current.row?.takeIf { it.editableSpeech } ?: return null
        val editor = editableView?.takeIf { it.isFocused && it.isShown } ?: return null
        val projection = imageRenderer.read(editor)
        val cursor = editor.selectionEnd.coerceIn(0, editor.length())
        val caret = projection.caretForEditor(cursor)
        val serializedOffset = projection.rawText().take(caret.rawOffset).length +
            projection.blocks.sumOf { block ->
                val precedes = block.rawOffset < caret.rawOffset ||
                    (block.rawOffset == caret.rawOffset && block.orderAtOffset < caret.orderAtOffset)
                if (!precedes) 0 else block.images.sumOf { it.marker.length } +
                    block.markerSeparators.sumOf(String::length)
            }
        return MeetingPanelAnchor(id, serializedOffset)
    }

    private fun bindEditable(turnId: String, projection: TranscriptImageProjection, label: String?) {
        removeReadOnlyView()
        val editor = editableView ?: createEditor().also {
            editableView = it
            bodyHost.addView(it, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        editor.visibility = View.VISIBLE
        editor.tag = "meeting-editor:$turnId"
        editor.contentDescription = label?.let { "Paroles de $it" } ?: "Paroles de la réunion"
        editor.setTextColor(palette.ink)
        editor.setHintTextColor(palette.inkMuted)
        editor.canEdit = { entry?.row?.editableSpeech == true }
        editor.acquireWindow = { callbacks?.acquireWindow() }
        editor.finishEditing = { callbacks?.finishEditing() }
        val selectionStart = editor.selectionStart.takeIf { editor.hasFocus() }
        val selectionEnd = editor.selectionEnd.takeIf { editor.hasFocus() }
        suppressTextWatcher = true
        try {
            imageRenderer.render(editor, projection, selectionStart, selectionEnd)
            lastEmittedText = projection.serializedNoteText()
        } finally {
            suppressTextWatcher = false
        }
    }

    private fun createEditor(): OverlayTranscriptEditor = OverlayTranscriptEditor(context).apply {
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(palette.ink)
        setHintTextColor(palette.inkMuted)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        minHeight = dp(48)
        minLines = 1
        maxLines = 6
        isSingleLine = false
        setHorizontallyScrolling(false)
        background = null
        showSoftInputOnFocus = false
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressTextWatcher) return
                publishCurrentText(this@apply)
            }
        })
    }

    private fun publishCurrentText(editor: OverlayTranscriptEditor) {
        val current = entry ?: return
        val turnId = current.turnId ?: return
        if (current.row?.editableSpeech != true) return
        val serialized = imageRenderer.read(editor).serializedNoteText()
        if (serialized == lastEmittedText) return
        lastEmittedText = serialized
        val owner = callbacks ?: return
        notifyingAction = true
        try {
            owner.edit(turnId, serialized)
        } finally {
            notifyingAction = false
        }
    }

    private fun bindReadOnly(projection: TranscriptImageProjection, orphanImages: Boolean) {
        val editor = readOnlyView ?: TextView(context).also {
            readOnlyView = it
            bodyHost.addView(it, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        editor.visibility = View.VISIBLE
        editor.text = imageSpanned(projection)
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        editor.setTextColor(palette.ink)
        editor.setPadding(dp(8), dp(6), dp(8), dp(6))
        editor.minHeight = dp(48)
        editor.contentDescription = if (orphanImages) "Images jointes sans ancrage de parole" else "Images jointes"
        editor.isFocusable = false
        editor.isLongClickable = false
    }

    private fun imageSpanned(projection: TranscriptImageProjection): CharSequence {
        val text = SpannableString(projection.editorText)
        projection.blocks.forEach { block ->
            val index = projection.editorOffsetForCaret(TranscriptImageCaret(block.rawOffset, block.orderAtOffset))
            if (index in 0 until text.length && text[index] == TranscriptImageBlocks.OBJECT_REPLACEMENT) {
                text.setSpan(OverlayImageBlockSpan(context, block), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return text
    }

    private fun bindImageActions(newEntry: MeetingPanelEntry) {
        imageActionContent.removeAllViews()
        if (newEntry.images.isEmpty()) {
            imageActions.visibility = View.GONE
            return
        }
        imageActions.visibility = View.VISIBLE
        newEntry.images.forEach { image ->
            val button = Button(context).apply {
                text = "${image.kind.label} ${image.number}"
                contentDescription = "Ouvrir les actions pour ${image.kind.label.lowercase()} ${image.number}"
                minHeight = dp(48)
                minimumHeight = dp(48)
                isFocusable = true
                isFocusableInTouchMode = false
                isAllCaps = false
                setOnClickListener { callbacks?.imageAction(newEntry, image) }
            }
            styleImageButton(button)
            imageActionContent.addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)))
        }
    }

    private fun updateSpeakerLabel(label: String? = entry?.row?.label) {
        labelView.text = if (!compact || label.isNullOrBlank()) label.orEmpty() else {
            compactSpeakerLabel(label)
        }
        labelView.contentDescription = label?.let { "Attribuer la prise de parole : $it" }
    }

    private fun compactSpeakerLabel(label: String): String {
        if (label.startsWith("Personne ")) return "P${label.removePrefix("Personne ")}"
        val firstCodePointEnd = Character.charCount(label.codePointAt(0))
        return label.substring(0, firstCodePointEnd).uppercase()
    }

    private fun styleImageButton(button: Button) {
        button.setTextColor(palette.ink)
        button.background = GradientDrawable().apply {
            setColor(palette.raised)
            cornerRadius = dp(8).toFloat()
        }
    }

    private fun removeEditableEditor() {
        editableView?.let { bodyHost.removeView(it) }
        editableView = null
        lastEmittedText = null
    }

    private fun removeReadOnlyView() {
        readOnlyView?.let { bodyHost.removeView(it) }
        readOnlyView = null
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
