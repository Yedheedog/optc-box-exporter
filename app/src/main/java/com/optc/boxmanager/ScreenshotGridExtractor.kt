package com.optc.boxmanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/**
 * Memory-bounded pure Android image processing. No native/OpenCV calls are made.
 * Detects aligned square-ish card candidates for review; it never assigns IDs or quantities.
 */
object ScreenshotGridExtractor {
    private data class Tile(val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun extract(context: Context, profileId: String, screenshot: File): List<File> {
        require(profileId.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid box profile ID." }
        val profileDirectory = BoxStore(context).profileDirectory(profileId).canonicalFile
        val source = screenshot.canonicalFile
        require(source.isFile && source.path.startsWith(profileDirectory.path + File.separator)) {
            "The source screenshot must belong to the selected box profile."
        }
        require(source.length() in 1..(30L * 1024L * 1024L)) {
            "Screenshot is empty or exceeds the 30 MiB limit."
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This screenshot image could not be decoded." }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 1200) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        ) ?: error("This screenshot image could not be decoded.")
        val preview = if (max(decoded.width, decoded.height) > 1200) {
            val scale = 1200f / max(decoded.width, decoded.height)
            Bitmap.createScaledBitmap(decoded, max(1, (decoded.width * scale).toInt()),
                max(1, (decoded.height * scale).toInt()), true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else decoded

        val width = preview.width
        val height = preview.height
        val pixels = IntArray(width * height)
        preview.getPixels(pixels, 0, width, 0, 0, width, height)
        preview.recycle()

        val verticalScores = IntArray(width)
        val horizontalScores = IntArray(height)
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val index = row + x
                if (abs(luminance(pixels[index + 1]) - luminance(pixels[index - 1])) >= 58) {
                    verticalScores[x]++
                }
                if (abs(luminance(pixels[index + width]) - luminance(pixels[index - width])) >= 58) {
                    horizontalScores[y]++
                }
            }
        }
        val verticalLines = findProjectionLines(verticalScores, height, 0.075f)
        val horizontalLines = findProjectionLines(horizontalScores, width, 0.075f)
        pixels.fill(0)

        val tiles = mutableListOf<Tile>()
        for (yi in 0 until horizontalLines.lastIndex) {
            val top = horizontalLines[yi]
            val bottom = horizontalLines[yi + 1]
            val tileHeight = bottom - top
            if (tileHeight < height * 0.07f || tileHeight > height * 0.48f) continue
            for (xi in 0 until verticalLines.lastIndex) {
                val left = verticalLines[xi]
                val right = verticalLines[xi + 1]
                val tileWidth = right - left
                if (tileWidth < width * 0.07f || tileWidth > width * 0.48f) continue
                val aspect = tileHeight.toFloat() / max(1, tileWidth)
                val areaRatio = tileWidth.toDouble() * tileHeight / (width.toDouble() * height)
                if (aspect in 0.78f..1.28f && areaRatio in 0.008..0.16) {
                    tiles += Tile(left, top, right, bottom)
                }
            }
        }
        val uniqueTiles = tiles.distinctBy { listOf(it.left, it.top, it.right, it.bottom) }
        require(uniqueTiles.isNotEmpty()) {
            "No aligned card grid was detected. Use manual crop review for this screenshot."
        }
        require(uniqueTiles.size <= 100) {
            "The detector found too many candidate regions (\${uniqueTiles.size}). Use manual crop review."
        }

        val decoder = BitmapRegionDecoder.newInstance(source.absolutePath, false)
            ?: error("Android could not open the original screenshot for cropping.")
        try {
            val outputDirectory = File(profileDirectory, "extracted_portraits").apply { mkdirs() }
            val scaleX = decoder.width.toFloat() / width
            val scaleY = decoder.height.toFloat() / height
            return uniqueTiles.map { tile ->
                val left = (tile.left * scaleX).toInt().coerceIn(0, decoder.width - 1)
                val top = (tile.top * scaleY).toInt().coerceIn(0, decoder.height - 1)
                val right = (tile.right * scaleX).toInt().coerceIn(left + 1, decoder.width)
                val bottom = (tile.bottom * scaleY).toInt().coerceIn(top + 1, decoder.height)
                var cropSample = 1
                while (max(right - left, bottom - top) / cropSample > 1000) cropSample *= 2
                val crop = decoder.decodeRegion(Rect(left, top, right, bottom),
                    BitmapFactory.Options().apply {
                        inSampleSize = cropSample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }) ?: error("Could not decode one detected card image.")
                try {
                    val name = "portrait_auto_\${UUID.randomUUID()}.jpg"
                    val destination = File(outputDirectory, name)
                    val temporary = File(outputDirectory, "\$name.tmp")
                    try {
                        FileOutputStream(temporary).use { output ->
                            check(crop.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                                "Could not encode a detected card image."
                            }
                            output.fd.sync()
                        }
                        check(temporary.renameTo(destination)) { "Could not save a detected card image." }
                    } finally {
                        if (temporary.exists()) temporary.delete()
                    }
                    destination
                } finally {
                    crop.recycle()
                }
            }
        } finally {
            decoder.recycle()
        }
    }

    private fun luminance(color: Int): Int {
        val red = color shr 16 and 0xff
        val green = color shr 8 and 0xff
        val blue = color and 0xff
        return (red * 30 + green * 59 + blue * 11) / 100
    }

    private fun findProjectionLines(scores: IntArray, orthogonalLength: Int, threshold: Float): List<Int> {
        val minimum = (orthogonalLength * threshold).toInt().coerceAtLeast(5)
        val peaks = mutableListOf<Int>()
        var start = -1
        for (index in scores.indices) {
            if (scores[index] >= minimum) {
                if (start < 0) start = index
            } else if (start >= 0) {
                peaks += (start + index - 1) / 2
                start = -1
            }
        }
        if (start >= 0) peaks += (start + scores.lastIndex) / 2
        val merged = mutableListOf<Int>()
        peaks.forEach { peak ->
            val last = merged.lastOrNull()
            if (last == null || peak - last > 5) merged += peak
            else merged[merged.lastIndex] = (last + peak) / 2
        }
        return merged
    }
}
