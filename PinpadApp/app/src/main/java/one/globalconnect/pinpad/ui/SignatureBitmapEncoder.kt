package one.globalconnect.pinpad.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import one.globalconnect.pinpad.protocol.SignatureImageFormat
import java.io.ByteArrayOutputStream

data class SignaturePoint(val x: Float, val y: Float)

object SignatureBitmapEncoder {
    fun encode(
        strokes: List<List<SignaturePoint>>,
        width: Int,
        height: Int,
        format: SignatureImageFormat,
    ): ByteArray {
        require(width > 0 && height > 0) { "Signature canvas has invalid dimensions" }
        require(strokes.any { it.size > 1 }) { "Signature is empty" }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = (minOf(width, height) / 85f).coerceIn(2.5f, 7f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE
        }
        strokes.forEach { stroke ->
            stroke.zipWithNext().forEach { (start, end) ->
                canvas.drawLine(start.x, start.y, end.x, end.y, paint)
            }
        }

        return ByteArrayOutputStream().use { output ->
            val compressFormat = when (format) {
                SignatureImageFormat.Png -> Bitmap.CompressFormat.PNG
                SignatureImageFormat.Jpeg -> Bitmap.CompressFormat.JPEG
            }
            check(bitmap.compress(compressFormat, JPEG_QUALITY, output)) {
                "Unable to encode signature image"
            }
            output.toByteArray()
        }.also {
            bitmap.recycle()
        }
    }

    private const val JPEG_QUALITY = 92
}
