package com.kafkasl.phonewhisper

import org.junit.Assert.*
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

}
