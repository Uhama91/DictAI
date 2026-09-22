package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostprocessingDiagnosticNotePublicationTest {
    @Test fun `all note destinations map to a saved note`() {
        for (destination in listOf(
            NoteInteractionPolicy.Destination.NOTE,
            NoteInteractionPolicy.Destination.NOTE_LIST,
            NoteInteractionPolicy.Destination.NOTE_EXPORT,
        )) {
            assertEquals(
                PostprocessingDiagnostic.PublicationResult.NOTE_SAVED,
                PostprocessingDiagnostic.PublicationResult.fromDestination(destination),
            )
        }
    }

    @Test fun `message has no publication result until injection succeeds or fails`() {
        assertNull(
            PostprocessingDiagnostic.PublicationResult.fromDestination(
                NoteInteractionPolicy.Destination.MESSAGE,
            )
        )
        assertEquals(
            PostprocessingDiagnostic.PublicationResult.INSERTED,
            PostprocessingDiagnostic.PublicationResult.fromDestination(
                NoteInteractionPolicy.Destination.MESSAGE,
                InjectionResult.Inserted,
            ),
        )
        assertEquals(
            PostprocessingDiagnostic.PublicationResult.COPIED,
            PostprocessingDiagnostic.PublicationResult.fromDestination(
                NoteInteractionPolicy.Destination.MESSAGE,
                InjectionResult.Copied,
            ),
        )
        assertEquals(
            PostprocessingDiagnostic.PublicationResult.FAILED,
            PostprocessingDiagnostic.PublicationResult.fromDestination(
                NoteInteractionPolicy.Destination.MESSAGE,
                InjectionResult.Failed,
            ),
        )
    }

    @Test fun `note publication has its own boundary and never claims message insertion`() {
        val report = PostprocessingDiagnostic.report(
            version = "0.9.10",
            timestampMs = 0,
            formatId = "email",
            requested = PostprocessingDiagnostic.Requested.LOCAL,
            applied = PostprocessingDiagnostic.Applied.ORIGINAL,
            local = null,
            runtime = "not-loaded",
            postprocessMs = 12,
            stopToPublicationMs = 37,
            finalText = "Texte confidentiel à ne pas conserver dans le diagnostic",
            publication = PostprocessingDiagnostic.PublicationResult.NOTE_SAVED,
            cloudSuppressed = false,
        )

        assertTrue(report.contains("Fin → enregistrement de la note : 37 ms"))
        assertTrue(report.contains("Publication : note enregistrée"))
        assertTrue(report.contains("Lignes non vides : 1"))
        assertTrue(!report.contains("Publication : inséré"))
        assertTrue(!report.contains("Publication : copié"))
        assertTrue(!report.contains("Texte confidentiel"))
    }
}
