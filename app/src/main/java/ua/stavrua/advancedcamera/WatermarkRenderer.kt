package ua.stavrua.advancedcamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max

object WatermarkRenderer {
    fun draw(source: Bitmap, metadata: CaptureMetadata, settings: CameraSettings, config: OverlayConfig): Bitmap {
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val baseSize = source.width * 0.021f * settings.watermarkScale
        val maxWidth = max(1, source.width - source.width / 12)
        val preliminaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-monospace", Typeface.BOLD)
            textSize = baseSize
        }
        val lines = MetadataCollector.lines(metadata, config).flatMap { wrap(it, maxWidth, preliminaryPaint) }
        if (lines.isEmpty()) return bitmap
        val heightLimitedSize = source.height * 0.72f / (lines.size * 1.38f + 1.24f)
        val textSize = baseSize.coerceAtMost(heightLimitedSize).coerceIn(14f, 240f)
        val padding = textSize * 0.62f
        val rowHeight = textSize * 1.38f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-monospace", Typeface.BOLD)
            this.textSize = textSize
        }
        val boxHeight = padding * 2 + rowHeight * lines.size
        val left = source.width * 0.025f
        val right = source.width - left
        val top = (source.height - boxHeight - source.height * 0.025f).coerceAtLeast(source.height * 0.01f)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF080B0D.toInt()
            alpha = (settings.watermarkOpacity * 220).toInt().coerceIn(0, 255)
        }
        canvas.drawRoundRect(RectF(left, top, right, top + boxHeight), textSize * 0.35f, textSize * 0.35f, boxPaint)
        paint.color = 0xFFFFFFFF.toInt()
        paint.alpha = (settings.watermarkOpacity * 255).toInt().coerceIn(0, 255)
        val accent = Paint(paint).apply {
            color = 0xFF80CBC4.toInt()
            alpha = paint.alpha
        }
        val baseline = top + padding + textSize
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, left + padding, baseline + index * rowHeight, if (index == 0 && metadata.customTitle.isNotBlank()) accent else paint)
        }
        return bitmap
    }

    private fun wrap(text: String, maxWidth: Int, paint: Paint): List<String> {
        if (paint.measureText(text) <= maxWidth) return listOf(text)
        val words = text.split(' ')
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        words.forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && paint.measureText(candidate) > maxWidth) {
                lines += current.toString()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines.ifEmpty { listOf(text.take(110)) }
    }
}
