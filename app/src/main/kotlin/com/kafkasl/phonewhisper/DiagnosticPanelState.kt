package com.kafkasl.phonewhisper

/** The two persisted post-processing reports that can be viewed independently. */
internal enum class DiagnosticPanel {
    LATEST_DICTATION,
    LAST_FORMAT,
}

internal data class DiagnosticPanelState(
    val title: String,
    val report: String?,
    val alternatePanel: DiagnosticPanel?,
    val alternateTitle: String?,
)

internal fun diagnosticPanelState(
    latest: String?,
    formatted: String?,
    requested: DiagnosticPanel = DiagnosticPanel.LATEST_DICTATION,
): DiagnosticPanelState {
    val resolvedPanel = when {
        requested == DiagnosticPanel.LATEST_DICTATION && latest != null -> DiagnosticPanel.LATEST_DICTATION
        requested == DiagnosticPanel.LAST_FORMAT && formatted != null -> DiagnosticPanel.LAST_FORMAT
        latest != null -> DiagnosticPanel.LATEST_DICTATION
        formatted != null -> DiagnosticPanel.LAST_FORMAT
        else -> requested
    }
    val report = when (resolvedPanel) {
        DiagnosticPanel.LATEST_DICTATION -> latest
        DiagnosticPanel.LAST_FORMAT -> formatted
    }
    val alternatePanel = when (resolvedPanel) {
        DiagnosticPanel.LATEST_DICTATION ->
            if (formatted != null && formatted != latest) DiagnosticPanel.LAST_FORMAT else null
        DiagnosticPanel.LAST_FORMAT ->
            if (latest != null && latest != formatted) DiagnosticPanel.LATEST_DICTATION else null
    }
    return DiagnosticPanelState(
        title = when (resolvedPanel) {
            DiagnosticPanel.LATEST_DICTATION -> "Dernière dictée"
            DiagnosticPanel.LAST_FORMAT -> "Dernier format demandé"
        },
        report = report,
        alternatePanel = alternatePanel,
        alternateTitle = alternatePanel?.let {
            when (it) {
                DiagnosticPanel.LATEST_DICTATION -> "Dernière dictée"
                DiagnosticPanel.LAST_FORMAT -> "Dernier format"
            }
        },
    )
}

internal fun diagnosticReportVersion(report: String): String? {
    val applicationLine = report.lineSequence()
        .firstOrNull { it.trimStart().startsWith("Application") }
        ?: return null
    return applicationLine.substringAfter(':', "").trim().takeIf { it.isNotEmpty() }
}

internal fun diagnosticDialogMessage(installedVersion: String, report: String?): String {
    if (report == null) {
        return "Version installée actuelle : $installedVersion\n\n" +
            "Aucun post-traitement enregistré. Terminez une dictée ou une note pour produire un diagnostic."
    }
    val reportVersion = diagnosticReportVersion(report)
    return buildString {
        append("Version installée actuelle : ")
        append(installedVersion)
        if (reportVersion != null && reportVersion != installedVersion) {
            append('\n')
            append("Ancien rapport conservé · version : ")
            append(reportVersion)
        }
        append("\n\n")
        append(report)
    }
}

internal fun diagnosticCopyText(installedVersion: String, report: String): String {
    return diagnosticDialogMessage(installedVersion, report)
}
