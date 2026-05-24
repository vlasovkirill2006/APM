package com.example.blindassist

import android.media.Image

/**
 * Compact CPU copy of YUV_420_888:
 * - [y]: full-res [width * height]
 * - [u], [v]: half-res [(width/2) * (height/2)] row-major
 */
data class YuvSnapshot(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
) {
    companion object {
        fun fromImage(img: Image): YuvSnapshot {
            val w = img.width
            val h = img.height
            val yPlane = img.planes[0]
            val uPlane = img.planes[1]
            val vPlane = img.planes[2]

            val yRowStride = yPlane.rowStride
            val uRowStride = uPlane.rowStride
            val vRowStride = vPlane.rowStride
            val uPixelStride = uPlane.pixelStride
            val vPixelStride = vPlane.pixelStride

            val yBytes = ByteArray(w * h)
            val yBuf = yPlane.buffer.duplicate()
            var dst = 0
            for (row in 0 until h) {
                yBuf.position(row * yRowStride)
                yBuf.get(yBytes, dst, w)
                dst += w
            }

            val cw = w / 2
            val ch = h / 2
            val uBytes = ByteArray(cw * ch)
            val vBytes = ByteArray(cw * ch)
            val uBuf = uPlane.buffer.duplicate()
            val vBuf = vPlane.buffer.duplicate()

            var ui = 0
            for (row in 0 until ch) {
                for (col in 0 until cw) {
                    uBytes[ui] = uBuf.get(row * uRowStride + col * uPixelStride)
                    vBytes[ui] = vBuf.get(row * vRowStride + col * vPixelStride)
                    ui++
                }
            }

            return YuvSnapshot(w, h, yBytes, uBytes, vBytes)
        }
    }
}
