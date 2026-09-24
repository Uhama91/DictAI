package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteExportSizingTest {
    @Test
    fun `camera photos fit a compact box without changing proportions`() {
        val landscape = NoteExportImageSizing.fit(NoteImageKind.CAMERA, 1600, 900)
        val portrait = NoteExportImageSizing.fit(NoteImageKind.CAMERA, 900, 1600)

        assertEquals(340f, landscape.widthPt, 0.01f)
        assertEquals(191.25f, landscape.heightPt, 0.01f)
        assertEquals(129.375f, portrait.widthPt, 0.01f)
        assertEquals(230f, portrait.heightPt, 0.01f)
        assertRatio(1600, 900, landscape)
        assertRatio(900, 1600, portrait)
    }

    @Test
    fun `screenshots fit a larger document box`() {
        val screenshot = NoteExportImageSizing.fit(NoteImageKind.SCREENSHOT, 1080, 2400)
        val tallScreenshot = NoteExportImageSizing.fit(NoteImageKind.SCREENSHOT, 480, 3600)

        assertEquals(189f, screenshot.widthPt, 0.01f)
        assertEquals(420f, screenshot.heightPt, 0.01f)
        assertEquals(56f, tallScreenshot.widthPt, 0.01f)
        assertEquals(420f, tallScreenshot.heightPt, 0.01f)
        assertRatio(1080, 2400, screenshot)
        assertRatio(480, 3600, tallScreenshot)
    }

    @Test
    fun `small source images are never enlarged`() {
        val result = NoteExportImageSizing.fit(NoteImageKind.CAMERA, 120, 80)

        assertEquals(120f, result.widthPt, 0.01f)
        assertEquals(80f, result.heightPt, 0.01f)
    }

    @Test
    fun `source dimensions must be positive`() {
        assertTrue(runCatching { NoteExportImageSizing.fit(NoteImageKind.CAMERA, 0, 100) }.isFailure)
        assertTrue(runCatching { NoteExportImageSizing.fit(NoteImageKind.CAMERA, 100, -1) }.isFailure)
    }

    private fun assertRatio(sourceWidth: Int, sourceHeight: Int, fitted: NoteExportImageSize) {
        assertEquals(sourceWidth.toFloat() / sourceHeight, fitted.widthPt / fitted.heightPt, 0.0001f)
    }
}
