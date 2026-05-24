package com.example.blindassist

import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder


object DepthDistanceEstimator {
    fun estimateMeters(frame: Frame): Float? {
        try {
            val depthImage = frame.acquireDepthImage16Bits()
            depthImage.use { img ->
                val w = img.width
                val h = img.height
                if (w <= 0 || h <= 0) return null

                val plane = img.planes[0]
                val buffer = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                if (pixelStride != 2) {
                    return null
                }

                val cx = w / 2
                val cy = h / 2
                val window = 5
                val valuesMm = ArrayList<Int>(window * window)

                val half = window / 2
                for (dy in -half..half) {
                    val y = (cy + dy).coerceIn(0, h - 1)
                    for (dx in -half..half) {
                        val x = (cx + dx).coerceIn(0, w - 1)
                        val offset = y * rowStride + x * pixelStride
                        if (offset + 1 >= buffer.limit()) continue
                        val mm = buffer.getShort(offset).toInt() and 0xFFFF
                        if (mm > 0) valuesMm.add(mm)
                    }
                }

                if (valuesMm.isEmpty()) return null
                valuesMm.sort()
                val medianMm = valuesMm[valuesMm.size / 2]
                return medianMm / 1000.0f
            }
        } catch (_: NotYetAvailableException) {
            return null
        } catch (_: Throwable) {
            return null
        }
    }
}

