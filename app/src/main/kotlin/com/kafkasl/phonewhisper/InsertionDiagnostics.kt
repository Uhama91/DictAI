package com.kafkasl.phonewhisper

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog
import java.text.DateFormat
import java.util.Date

/** Local structural diagnosis. Never store field contents, dictated text or clipboard contents. */
internal object InsertionDiagnostics {
    fun insert(context: Context, text: String, preserveImageClipboard: Boolean): InjectionResult {
        val controller = InjectionGateway.current()
        val status = AccessibilitySetup.status(context)
        val result = injectOrCopy(controller, text, { DictationClipboard.copy(context, it) }, preserveImageClipboard)
        val issue = when {
            result == InjectionResult.Inserted -> "Texte inséré dans le champ."
            controller == null -> when (status) {
                AccessibilityServiceStatus.ENABLED_NOT_CONNECTED -> "Autorisation active, mais service déconnecté. Désactivez puis réactivez DictAI dans les réglages d’accessibilité."
                else -> "Activez l’insertion automatique dans les réglages d’accessibilité de DictAI."
            }
            else -> (controller as? WhisperAccessibilityService)?.lastInsertionIssue
                ?: "L’application n’a pas accepté l’insertion. Touchez son champ de texte, puis réessayez."
        }
        val report = buildString {
            appendLine("DictAI ${BuildConfig.VERSION_NAME}")
            appendLine(DateFormat.getDateTimeInstance().format(Date()))
            appendLine("Service : ${status.subtitle}")
            appendLine("Résultat : ${when (result) {
                InjectionResult.Inserted -> "inséré"
                InjectionResult.Copied -> "copié uniquement — collage manuel nécessaire"
                InjectionResult.Failed -> "échec de l’insertion et de la copie"
            }}")
            appendLine()
            appendLine(issue)
            appendLine()
            append("Ce diagnostic ne contient ni la dictée ni le contenu du champ.")
        }
        context.getSharedPreferences("insertion_diagnostics", Context.MODE_PRIVATE).edit().putString("report", report).apply()
        return result
    }

    fun report(context: Context): String = context.getSharedPreferences("insertion_diagnostics", Context.MODE_PRIVATE)
        .getString("report", null) ?: "Aucune tentative d’insertion enregistrée. Touchez un champ dans une autre application, dictez puis terminez la dictée."

    fun show(activity: Activity) {
        AlertDialog.Builder(activity).setTitle("Dernière insertion").setMessage(report(activity))
            .setPositiveButton("Réglages d’insertion") { _, _ -> AccessibilitySetup.showHelp(activity) }
            .setNeutralButton("Copier le diagnostic") { _, _ -> DictationClipboard.copy(activity, report(activity)) }
            .setNegativeButton("Fermer", null).show()
    }
}
