package com.kafkasl.phonewhisper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import java.text.DateFormat
import java.util.Date

class DictationHistoryActivity : AppCompatActivity() {
    private val history by lazy { DictationHistory(this) }
    private var selectedId: String? = null
    private val palette get() = ThemeTokens.palette(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedId = savedInstanceState?.getString("selected")
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { back() }
        })
    }
    override fun onResume() { super.onResume(); render() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected", selectedId); super.onSaveInstanceState(outState)
    }
    private fun back() { if (selectedId != null) { selectedId = null; render() } else finish() }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(28))
            background = NotebookBackgroundDrawable(this@DictationHistoryActivity)
        }
        root.addView(body(if (selectedId == null) "‹ Retour" else "‹ Historique", 17f, palette.green).apply {
            minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true; setOnClickListener { back() }
        })
        root.addView(body("Historique des dictées", 34f, palette.ink).apply {
            ResourcesCompat.getFont(this@DictationHistoryActivity, R.font.caveat)?.let { typeface = it }
            setPadding(0, dp(8), 0, dp(14))
        })
        val entries = try { history.all() } catch (_: Exception) {
            root.addView(body("L’historique ne peut pas être lu. Son contenu est conservé. Vous pouvez le vider pour recommencer.", 15f, palette.errorText))
            root.addView(action("Vider l’historique") { confirmClear() })
            setContentView(ScrollView(this).apply { addView(root) }); return
        }
        val selected = entries.firstOrNull { it.id == selectedId }
        if (selected != null) {
            root.addView(body(date(selected), 13f, palette.inkMuted).apply { setPadding(0, 0, 0, dp(16)) })
            root.addView(action("Copier le texte") {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("DictAI · Dictée", selected.text))
                Toast.makeText(this, "Texte copié", Toast.LENGTH_SHORT).show()
            })
            root.addView(body(selected.text, 17f, palette.ink).apply {
                setPadding(0, dp(22), 0, dp(24)); setTextIsSelectable(true)
            })
        } else {
            selectedId = null
            root.addView(body("Les 10 dernières dictées sur cet appareil. La suivante remplace la plus ancienne. Aucun audio n’est conservé.", 14f, palette.inkMuted).apply { setPadding(0, 0, 0, dp(18)) })
            if (entries.isEmpty()) {
                root.addView(body("Aucune dictée récente", 20f, palette.ink).apply { setPadding(0, dp(24), 0, dp(10)) })
                root.addView(body("Vos prochaines dictées apparaîtront ici, même si leur insertion échoue.", 15f, palette.inkMuted))
            } else {
                root.addView(body("${entries.size} / 10 · De la plus récente à la plus ancienne", 12f, palette.inkMuted).apply { setPadding(0, 0, 0, dp(12)) })
                entries.forEach { entry ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
                        background = GradientDrawable().apply { setColor(palette.surface); cornerRadius = dp(10).toFloat() }
                        isClickable = true; isFocusable = true
                        setOnClickListener { selectedId = entry.id; render() }
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
                        contentDescription = "${date(entry)}. ${entry.text.take(160)}. Ouvrir la dictée."
                    }
                    row.addView(body(date(entry), 12f, palette.inkMuted))
                    row.addView(body(entry.text, 16f, palette.ink).apply {
                        maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(8), 0, 0)
                        setOnClickListener { selectedId = entry.id; render() }
                    })
                    root.addView(row)
                }
                root.addView(action("Vider l’historique") { confirmClear() })
            }
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    private fun confirmClear() {
        AlertDialog.Builder(this).setTitle("Vider l’historique ?")
            .setMessage("Les dictées récentes seront effacées de cet appareil. Vos notes sont conservées.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Vider") { _, _ ->
                try { history.clear(); selectedId = null; render() }
                catch (_: Exception) { Toast.makeText(this, "L’historique n’a pas pu être vidé.", Toast.LENGTH_LONG).show() }
            }.show()
    }
    private fun date(entry: RecentDictation) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.timestamp))
    private fun body(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun action(label: String, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label; isAllCaps = false; minHeight = dp(48)
        backgroundTintList = ColorStateList.valueOf(palette.green); setTextColor(palette.onGreen)
        typeface = Typeface.DEFAULT_BOLD; setOnClickListener { onClick() }
    }
    private fun dp(value: Int) = ThemeTokens.dp(this, value)
}
