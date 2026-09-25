package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteCameraGeometryTest {
    @Test fun cameraBuffersBoundSizeAndPreserveSupportedAspectRatio() {
        val sizes = listOf(NoteCameraGeometry.Size(4000,3000), NoteCameraGeometry.Size(1920,1080), NoteCameraGeometry.Size(1280,960), NoteCameraGeometry.Size(640,480))
        assertEquals(NoteCameraGeometry.Size(1920,1080), NoteCameraGeometry.chooseSize(sizes, 2048))
        assertEquals(NoteCameraGeometry.Size(1280,960), NoteCameraGeometry.chooseSize(sizes, 1280, 4.0/3))
        assertEquals(NoteCameraGeometry.Size(640,480), NoteCameraGeometry.chooseSize(sizes, 320))
    }

    @Test fun photoRotationHandlesRearFrontAndLandscape() {
        assertEquals(90, NoteCameraGeometry.jpegRotation(90,0,false))
        assertEquals(0, NoteCameraGeometry.jpegRotation(90,90,false))
        assertEquals(180, NoteCameraGeometry.jpegRotation(90,90,true))
        assertEquals(180, NoteCameraGeometry.jpegRotation(90,270,false))
        assertEquals(0, NoteCameraGeometry.jpegRotation(270,270,false))
    }

    @Test fun previewKeepsFrameAspectWithoutStretchOrClipping() {
        for (sensor in listOf(0,90,270)) for (display in listOf(0,90,180,270)) {
            val (sx,sy) = NoteCameraGeometry.previewScale(1280,720,sensor,display,320,250)
            val width = 320*sx; val height = 250*sy
            val uprightRatio = if (sensor % 180 == 0) 1280.0/720 else 720.0/1280
            assertEquals(uprightRatio, (width/height).toDouble(), .0001)
            val finalWidth = if (display % 180 == 0) width else height
            val finalHeight = if (display % 180 == 0) height else width
            assertTrue(finalWidth <= 320.001 && finalHeight <= 250.001)
        }
    }

    @Test
    fun accessibleZoomControlMapsToTheCameraRangeAndStaysBounded() {
        assertEquals(1f, NoteCameraGeometry.zoomForProgress(0, 1f, 4f), 0.001f)
        assertEquals(2.5f, NoteCameraGeometry.zoomForProgress(50, 1f, 4f), 0.001f)
        assertEquals(4f, NoteCameraGeometry.zoomForProgress(100, 1f, 4f), 0.001f)
        assertEquals(1f, NoteCameraGeometry.zoomForProgress(-30, 1f, 4f), 0.001f)
        assertEquals(4f, NoteCameraGeometry.zoomForProgress(130, 1f, 4f), 0.001f)
        assertEquals(0.65f, NoteCameraGeometry.zoomForProgress(50, 0.5f, 0.8f), 0.001f)
    }

    @Test
    fun cropFallbackKeepsTheZoomCenteredAndInsideTheSensorBounds() {
        val full = NoteCameraGeometry.Rect(100, 50, 4100, 3050)

        assertEquals(full, NoteCameraGeometry.cropForZoom(full, 1f))
        assertEquals(
            NoteCameraGeometry.Rect(1100, 800, 3100, 2300),
            NoteCameraGeometry.cropForZoom(full, 2f),
        )
        val maximum = NoteCameraGeometry.cropForZoom(full, 99f)
        assertTrue(maximum.left >= full.left && maximum.top >= full.top)
        assertTrue(maximum.right <= full.right && maximum.bottom <= full.bottom)
        assertTrue(maximum.width > 0 && maximum.height > 0)
    }

    @Test
    fun compactExpandedAndLandscapeLayoutsRespectReducedScreens() {
        assertTrue(NoteCameraGeometry.dialogWidth(320, 1f, expanded = false) <= 296)
        assertEquals(296, NoteCameraGeometry.dialogWidth(320, 1f, expanded = true))
        assertEquals(620, NoteCameraGeometry.dialogWidth(800, 1f, expanded = false, landscape = true))
        assertEquals(776, NoteCameraGeometry.dialogWidth(800, 1f, expanded = true, landscape = true))

        val compactPortrait = NoteCameraGeometry.previewHeight(640, 1f, expanded = false, landscape = false)
        val expandedPortrait = NoteCameraGeometry.previewHeight(640, 1f, expanded = true, landscape = false)
        val landscape = NoteCameraGeometry.previewHeight(280, 1f, expanded = true, landscape = true)
        assertTrue(compactPortrait in 1..250)
        assertTrue(expandedPortrait > compactPortrait)
        assertEquals("the expanded viewfinder reserves space for the thumbnail strip and actions", 320, expandedPortrait)
        assertTrue("preview must leave the landscape command row visible", landscape <= 160)
        assertTrue(landscape >= 48)
    }
}
