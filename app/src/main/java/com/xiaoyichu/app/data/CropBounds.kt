package com.xiaoyichu.app.data

import kotlin.math.roundToInt

/** Normalized coordinates on the EXIF-oriented, complete source image. */
data class CropBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f })
        require(left < right && top < bottom)
    }
    fun pixels(width: Int, height: Int): IntArray {
        require(width > 0 && height > 0)
        val x = (left * width).roundToInt().coerceIn(0, width - 1)
        val y = (top * height).roundToInt().coerceIn(0, height - 1)
        val endX = (right * width).roundToInt().coerceIn(x + 1, width)
        val endY = (bottom * height).roundToInt().coerceIn(y + 1, height)
        return intArrayOf(x, y, endX - x, endY - y)
    }
    companion object { val Full = CropBounds(0f, 0f, 1f, 1f) }
}
