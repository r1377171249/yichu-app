package com.xiaoyichu.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CropBoundsTest {
    @Test fun fullFrameIncludesLastPixel() {
        assertArrayEquals(intArrayOf(0, 0, 1200, 800), CropBounds.Full.pixels(1200, 800))
    }
    @Test fun centeredCropUsesOrientedImageCoordinates() {
        val bounds = CropBounds(.1f, .1f, .9f, .9f)
        assertArrayEquals(intArrayOf(120, 80, 960, 640), bounds.pixels(1200, 800))
        assertArrayEquals(intArrayOf(40, 60, 320, 480), bounds.pixels(400, 600))
    }
    @Test fun subpixelSelectionAlwaysRetainsAtLeastOnePixelAtEdges() {
        assertArrayEquals(intArrayOf(0, 0, 1, 1), CropBounds(.99f, .99f, 1f, 1f).pixels(1, 1))
        assertArrayEquals(intArrayOf(9, 19, 1, 1), CropBounds(.999f, .999f, 1f, 1f).pixels(10, 20))
    }
    @Test fun invalidCoordinatesCannotReachBitmapOperations() {
        for (values in listOf(floatArrayOf(Float.NaN, 0f, 1f, 1f), floatArrayOf(-.1f, 0f, 1f, 1f),
            floatArrayOf(0f, 0f, 1.01f, 1f), floatArrayOf(.5f, 0f, .5f, 1f), floatArrayOf(0f, .8f, 1f, .2f))) {
            assertThrows(IllegalArgumentException::class.java) { CropBounds(values[0], values[1], values[2], values[3]) }
        }
    }
}
