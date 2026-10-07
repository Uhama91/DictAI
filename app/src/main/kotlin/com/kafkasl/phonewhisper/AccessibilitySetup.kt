package com.kafkasl.phonewhisper

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** Settings navigation only; Android always owns the user's accessibility consent. */
internal object AccessibilitySetup {
    fun status(context: Context): AccessibilityServiceStatus {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val listedByManager = runCatching {
            manager?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)?.any {
                val service = it.resolveInfo?.serviceInfo
                service?.packageName == context.packageName && service.name == WhisperAccessibilityService::class.java.name
            } == true
        }.getOrDefault(false)
        // Some Android states omit crashed/suspended services from the manager's list even
        // while the user's toggle stays on. Read the setting as well; never change it here.
        val enabledInSettings = runCatching {
            val expected = ComponentName(context, WhisperAccessibilityService::class.java)
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.any { ComponentName.unflattenFromString(it) == expected } == true
        }.getOrDefault(false)
        return AccessibilityServiceStatus.resolve(listedByManager || enabledInSettings, InjectionGateway.current() != null)
    }

    fun explanation(status: AccessibilityServiceStatus): String = when (status) {
        AccessibilityServiceStatus.CONNECTED -> "Service connecté. Touchez d’abord le champ dans l’autre application, puis dictez. Si l’insertion échoue, consultez « Dernière insertion » dans les réglages de DictAI."
        AccessibilityServiceStatus.ENABLED_NOT_CONNECTED -> "Android indique que DictAI est autorisé, mais le service n’est pas connecté. Dans les réglages qui suivent, désactivez puis réactivez DictAI, puis revenez ici."
        AccessibilityServiceStatus.DISABLED -> "Pour insérer vos dictées, activez DictAI dans les services d’accessibilité. Android lui permet alors de lire les champs affichés et d’y écrire ; ce service permet aussi les captures d’écran à votre demande. Vous pouvez continuer avec la copie manuelle."
    }

    fun showHelp(activity: Activity) {
        val status = status(activity)
        val builder = AlertDialog.Builder(activity)
            .setTitle("Insertion dans les applications")
            .setMessage(explanation(status))
            .setPositiveButton("Ouvrir les réglages") { _, _ -> openSettings(activity) }
            .setNegativeButton("Fermer", null)
        if (Build.VERSION.SDK_INT >= 33) builder.setNeutralButton("Activation bloquée ?") { _, _ -> showRestrictedHelp(activity) }
        builder.show()
    }

    fun openSettings(activity: Activity) {
        // The direct service-details route requires a system permission on Android 16.
        // Use the public route; do not depend on a privileged or manufacturer-specific API.
        openFirst(activity, listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), Intent(Settings.ACTION_SETTINGS)))
    }

    fun showRestrictedHelp(activity: Activity) {
        val xiaomi = (Build.MANUFACTURER + " " + Build.BRAND).lowercase().let {
            it.contains("xiaomi") || it.contains("redmi") || it.contains("poco")
        }
        val location = if (xiaomi) "Sur Xiaomi / HyperOS, cherchez « Autoriser les paramètres restreints » en bas de la fiche de DictAI ; selon la version, l’option peut être dans le menu ⋮."
            else "Dans la fiche de DictAI, ouvrez le menu ⋮ puis « Autoriser les paramètres restreints », si cette option est présente."
        AlertDialog.Builder(activity).setTitle("Si Android bloque l’activation")
            .setMessage("Une installation par fichier APK peut nécessiter cette étape sur Android 13 ou plus récent.\n\n$location\n\nRevenez ensuite activer DictAI dans les réglages d’accessibilité.")
            .setPositiveButton("Ouvrir la fiche DictAI") { _, _ ->
                openFirst(activity, listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))))
            }.setNegativeButton("Retour", null).show()
    }

    internal fun openFirst(activity: Activity, intents: List<Intent>) {
        for (intent in intents) if (runCatching { activity.startActivity(intent) }.isSuccess) return
        Toast.makeText(activity, "Ouvrez les réglages Android → Accessibilité → DictAI.", Toast.LENGTH_LONG).show()
    }
}
