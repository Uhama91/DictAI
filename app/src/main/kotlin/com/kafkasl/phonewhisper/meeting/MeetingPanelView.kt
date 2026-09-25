package com.kafkasl.phonewhisper.meeting

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kafkasl.phonewhisper.NoteImage
import com.kafkasl.phonewhisper.ThemePalette
import com.kafkasl.phonewhisper.ThemeTokens
import com.kafkasl.phonewhisper.TranscriptImageBlocks
import java.util.LinkedHashMap

/** Compact, overlay-owned panel. It creates views only; its host owns window and dialogs. */
internal class MeetingPanelView(context: Context) : LinearLayout(context) {
    private val toolbar = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val titleView = TextView(context)
    private val participantsButton = Button(context)
    private val actionsButton = Button(context)
    private val statusRow = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val statusView = TextView(context)
    private val cancelButton = Button(context)
    internal val recyclerView = RecyclerView(context)
    internal val adapter = MeetingPanelAdapter(context)
    private var status = MeetingPanelStatus()
    private var compact = false
    private var rowCallbacks: MeetingPanelRowCallbacks? = null
    private var palette = ThemeTokens.palette(context)
    private var pendingScrollRestore: Runnable? = null
    private var scrollRestoreGeneration = 0L

    internal var onParticipants: () -> Unit = {}
    internal var onActions: () -> Unit = {}
    internal var onCancel: () -> Unit = {}
    internal var onRetrySave: () -> Unit = {}

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.surface)
        clipToPadding = false
        toolbar.minimumHeight = dp(48)
        toolbar.addView(titleView, LayoutParams(0, dp(48), 1f))
        toolbar.addView(participantsButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)))
        toolbar.addView(actionsButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)))
        titleView.text = "Réunion"
        titleView.gravity = android.view.Gravity.CENTER_VERTICAL
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        titleView.maxLines = 1
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        participantsButton.text = "Intervenants (0)"
        participantsButton.contentDescription = "Ouvrir la liste des intervenants"
        actionsButton.text = "Actions"
        actionsButton.contentDescription = "Actions de la réunion"
        listOf(participantsButton, actionsButton).forEach { button ->
            button.minHeight = dp(48)
            button.minimumHeight = dp(48)
            button.isAllCaps = false
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            button.setPadding(dp(4), 0, dp(4), 0)
        }
        participantsButton.setOnClickListener { onParticipants() }
        actionsButton.setOnClickListener { onActions() }

        statusRow.minimumHeight = dp(24)
        statusRow.gravity = android.view.Gravity.CENTER_VERTICAL
        statusRow.addView(statusView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        statusRow.addView(cancelButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)))
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        statusView.maxLines = 1
        statusView.ellipsize = android.text.TextUtils.TruncateAt.END
        cancelButton.text = "Annuler"
        cancelButton.isAllCaps = false
        cancelButton.minHeight = dp(48)
        cancelButton.minimumHeight = dp(48)
        cancelButton.setOnClickListener { onCancel() }

        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter
        recyclerView.itemAnimator = null
        recyclerView.setHasFixedSize(false)
        recyclerView.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        addView(statusRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(recyclerView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        refreshTheme()
        renderStatus()
    }

    internal fun setRowCallbacks(callbacks: MeetingPanelRowCallbacks) {
        rowCallbacks = callbacks
        adapter.callbacks = callbacks
    }

    internal fun hasNotifyingEditor(): Boolean = visibleTurnEditors().any(MeetingTurnEditor::isNotifyingEdit)

    internal fun updateHeader(document: MeetingDocument, newStatus: MeetingPanelStatus) {
        status = newStatus
        val count = document.participants.size.coerceAtMost(8)
        participantCount = count
        participantsButton.text = "Intervenants ($count)"
        participantsButton.contentDescription = "Ouvrir les $count intervenants"
        renderStatus()
        requestLayout()
    }

    internal fun refreshTheme() {
        palette = ThemeTokens.palette(context)
        setBackgroundColor(palette.surface)
        titleView.setTextColor(palette.ink)
        statusView.setTextColor(palette.inkMuted)
        listOf(participantsButton, actionsButton, cancelButton).forEach { button ->
            button.setTextColor(palette.ink)
            button.background = buttonBackground(palette)
        }
        adapter.setPalette(palette)
        visibleTurnEditors().forEach { it.refreshTheme(palette) }
    }

    internal fun submitRows(rows: List<MeetingPanelEntry>) {
        val firstAnchor = viewportAnchor()
        val followTail = !recyclerView.canScrollVertically(1)
        val editing = recyclerView.findFocus() is com.kafkasl.phonewhisper.OverlayTranscriptEditor
        adapter.replaceRows(rows)
        pendingScrollRestore?.let(recyclerView::removeCallbacks)
        val generation = ++scrollRestoreGeneration
        val restore = Runnable {
            if (generation != scrollRestoreGeneration) return@Runnable
            pendingScrollRestore = null
            val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return@Runnable
            when {
                editing -> restoreAnchor(firstAnchor, manager)
                followTail && rows.isNotEmpty() -> manager.scrollToPosition(rows.lastIndex)
                else -> restoreAnchor(firstAnchor, manager)
            }
        }
        pendingScrollRestore = restore
        recyclerView.post(restore)
    }

    internal fun cancelPendingScrollRestore() {
        scrollRestoreGeneration++
        pendingScrollRestore?.let(recyclerView::removeCallbacks)
        pendingScrollRestore = null
    }

    internal fun visibleTurnEditors(): List<MeetingTurnEditor> = (0 until recyclerView.childCount)
        .mapNotNull { recyclerView.getChildAt(it) as? MeetingTurnEditor }

    internal fun focusedTurnEditor(): MeetingTurnEditor? {
        var current = recyclerView.findFocus() ?: return null
        while (current.parent is View && current !is MeetingTurnEditor) current = current.parent as View
        return current as? MeetingTurnEditor
    }

    internal fun findTurnEditor(turnId: String): MeetingTurnEditor? = visibleTurnEditors()
        .firstOrNull { it.entry?.turnId == turnId }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val height = MeasureSpec.getSize(heightMeasureSpec)
            val shouldCompact = height <= dp(132)
            if (compact != shouldCompact) {
                compact = shouldCompact
                statusRow.visibility = if (compact) View.GONE else View.VISIBLE
                adapter.setCompact(compact)
                visibleTurnEditors().forEach { it.setCompact(compact) }
                renderStatus()
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun renderStatus() {
        val summary = statusLabel(status)
        val error = status.saveError?.takeIf(String::isNotBlank)
        val inlineSummary = if (error == null) summary else "$summary · Échec de sauvegarde"
        titleView.text = if (compact) "Réunion · $inlineSummary" else "Réunion"
        titleView.contentDescription = buildString {
            append("Réunion, ").append(summary)
            error?.let { append(", échec de sauvegarde : ").append(it) }
        }
        statusView.text = buildString {
            append(summary)
            status.detail?.takeIf(String::isNotBlank)?.let { append(" · ").append(it) }
            error?.let { append(" · Échec de sauvegarde : ").append(it) }
        }
        val cancelVisible = status.phase == MeetingPanelStatus.Phase.LOADING ||
            status.phase == MeetingPanelStatus.Phase.DOWNLOADING
        val retryVisible = error != null
        cancelButton.visibility = if ((cancelVisible || retryVisible) && !compact) View.VISIBLE else View.GONE
        if (retryVisible && !compact) {
            cancelButton.text = "Réessayer la sauvegarde"
            cancelButton.contentDescription = "Réessayer la sauvegarde de la réunion"
            cancelButton.setOnClickListener { onRetrySave() }
        } else if (!compact && cancelVisible) {
            cancelButton.text = "Annuler"
            cancelButton.contentDescription = if (status.phase == MeetingPanelStatus.Phase.DOWNLOADING) {
                "Annuler le téléchargement du modèle"
            } else "Annuler le chargement du modèle"
            cancelButton.setOnClickListener { onCancel() }
        }
        if (compact && cancelVisible && !retryVisible) {
            actionsButton.text = "×"
            actionsButton.contentDescription = "Annuler ${if (status.phase == MeetingPanelStatus.Phase.DOWNLOADING) "le téléchargement" else "le chargement"}"
            actionsButton.setOnClickListener { onCancel() }
        } else {
            actionsButton.text = if (compact) "⋮" else "Actions"
            actionsButton.contentDescription = "Actions de la réunion"
            actionsButton.setOnClickListener { onActions() }
        }
        if (compact) {
            participantsButton.text = "Voix ${statusParticipantCount()}"
            participantsButton.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(48))
            actionsButton.layoutParams = LayoutParams(dp(48), dp(48))
        } else {
            participantsButton.text = "Intervenants (${statusParticipantCount()})"
            participantsButton.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(48))
            actionsButton.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(48))
        }
    }

    private var participantCount = 0

    private fun statusParticipantCount() = participantCount

    private fun statusLabel(status: MeetingPanelStatus): String = when (status.phase) {
        MeetingPanelStatus.Phase.READY -> "Prête"
        MeetingPanelStatus.Phase.LISTENING -> "Écoute en cours"
        MeetingPanelStatus.Phase.PAUSED -> "En pause"
        MeetingPanelStatus.Phase.PAUSING -> "Pause en cours"
        MeetingPanelStatus.Phase.LOADING -> "Chargement du modèle"
        MeetingPanelStatus.Phase.FINALIZING -> "Finalisation"
        MeetingPanelStatus.Phase.CLOSING -> "Fermeture en cours"
        MeetingPanelStatus.Phase.FINISHED -> "Réunion terminée"
        MeetingPanelStatus.Phase.ERROR -> status.detail?.takeIf(String::isNotBlank) ?: "Erreur"
        MeetingPanelStatus.Phase.DOWNLOADING -> {
            val progress = status.progressPercent?.coerceIn(0, 100)?.let { " $it %" }.orEmpty()
            val size = status.modelSize?.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
            "Téléchargement$progress$size"
        }
        MeetingPanelStatus.Phase.MODEL_UNAVAILABLE -> "Modèle indisponible"
        MeetingPanelStatus.Phase.DELAYED -> "Retard audio"
    }

    private fun viewportAnchor(): Pair<String, Int>? {
        val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return null
        val position = manager.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return null
        val child = manager.findViewByPosition(position) ?: return null
        val key = adapter.rowAt(position)?.stableKey ?: return null
        return key to child.top
    }

    private fun restoreAnchor(anchor: Pair<String, Int>?, manager: LinearLayoutManager) {
        if (anchor == null) return
        val position = adapter.positionOf(anchor.first)
        if (position >= 0) manager.scrollToPositionWithOffset(position, anchor.second)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun buttonBackground(colors: ThemePalette) = GradientDrawable().apply {
        setColor(colors.raised)
        cornerRadius = dp(8).toFloat()
    }
}

internal class MeetingPanelAdapter(context: Context) : RecyclerView.Adapter<MeetingPanelAdapter.Holder>() {
    private var palette = ThemeTokens.palette(context)
    private val stableIds = LinkedHashMap<String, Long>()
    private var nextStableId = 1L
    private var rows: List<MeetingPanelEntry> = emptyList()
    internal var callbacks: MeetingPanelRowCallbacks? = null
    private var compact = false

    init { setHasStableIds(true) }

    override fun getItemCount(): Int = rows.size

    override fun getItemId(position: Int): Long = stableIds.getOrPut(rows[position].stableKey) { nextStableId++ }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(MeetingTurnEditor(parent.context))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.editor.setCompact(compact)
        holder.editor.refreshTheme(palette)
        callbacks?.let { holder.editor.bind(rows[position], it) }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.editor.bindPlaceholder()
        super.onViewRecycled(holder)
    }

    internal fun rowAt(position: Int): MeetingPanelEntry? = rows.getOrNull(position)
    internal fun positionOf(stableKey: String): Int = rows.indexOfFirst { it.stableKey == stableKey }

    internal fun replaceRows(newRows: List<MeetingPanelEntry>) {
        val oldRows = rows
        val nextRows = newRows.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldRows.size
            override fun getNewListSize() = nextRows.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                oldRows[oldItemPosition].stableKey == nextRows[newItemPosition].stableKey
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                oldRows[oldItemPosition] == nextRows[newItemPosition]
        }, false)
        rows = nextRows
        diff.dispatchUpdatesTo(this)
    }

    internal fun setCompact(value: Boolean) {
        compact = value
    }

    internal fun setPalette(value: ThemePalette) {
        palette = value
    }

    internal class Holder(val editor: MeetingTurnEditor) : RecyclerView.ViewHolder(editor)
}
