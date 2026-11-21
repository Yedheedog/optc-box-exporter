package com.optc.boxmanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Offline, review-first detector ported from the project's legacy gradient-based
 * square detector. It extracts candidate square card tiles; it does not assign IDs
 * or increment box quantities.
 */
object ScreenshotGridExtractor {
    private data class Tile(val x: Int, val y: Int, val width: Int, val height: Int) {
        val area: Int get() = width * height
        val centerY: Float get() = y + height / 2f
    }

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
        check(OpenCVLoader.initLocal()) { "The offline image detector could not be initialized." }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This screenshot image could not be decoded." }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 1800) sample *= 2
        val preview = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: error("This screenshot image could not be decoded.")

        val previewWidth = preview.width
        val previewHeight = preview.height
        val rgba = Mat()
        val bgr = Mat()
        val white = Mat()
        val yellow = Mat()
        val mask = Mat()
        val masked = Mat()
        val sharpened = Mat()
        val gray = Mat()
        val edges = Mat()
        val lines = Mat()
        val binary = Mat()
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        val candidates = mutableListOf<Tile>()
        try {
            Utils.bitmapToMat(preview, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Core.inRange(bgr, Scalar(240.0, 240.0, 240.0), Scalar(255.0, 255.0, 255.0), white)
            Core.inRange(bgr, Scalar(0.0, 150.0, 150.0), Scalar(255.0, 255.0, 255.0), yellow)
            Core.bitwise_or(white, yellow, mask)
            Core.bitwise_and(bgr, bgr, masked, mask)

            val kernel = Mat(3, 3, CvType.CV_32F)
            try {
                kernel.put(0, 0, floatArrayOf(
                    -1f, -1f, -1f,
                    -1f, 9f, -1f,
                    -1f, -1f, -1f
                ))
                Imgproc.filter2D(masked, sharpened, -1, kernel)
            } finally {
                kernel.release()
            }
            Imgproc.cvtColor(sharpened, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.Canny(gray, edges, 50.0, 255.0)
            Imgproc.HoughLinesP(edges, lines, 0.1, Math.PI / 10.0, 150, 5.0, 4.0)

            for (index in 0 until lines.rows()) {
                val line = lines.get(index, 0) ?: continue
                if (line.size < 4) continue
                val x1 = line[0].toInt()
                val y1 = line[1].toInt()
                val x2 = line[2].toInt()
                val y2 = line[3].toInt()
                val dx = abs(x2 - x1)
                val dy = abs(y2 - y1)
                if (dy <= 1 && dx > preview.width * 0.05) {
                    Imgproc.line(edges, org.opencv.core.Point(0.0, y1.toDouble()),
                        org.opencv.core.Point(preview.width.toDouble(), y2.toDouble()), Scalar(255.0), 2)
                } else if (dx <= 1 && dy > preview.height * 0.05) {
                    Imgproc.line(edges, org.opencv.core.Point(x1.toDouble(), 0.0),
                        org.opencv.core.Point(x2.toDouble(), preview.height.toDouble()), Scalar(255.0), 2)
                }
            }
            Imgproc.threshold(edges, binary, 200.0, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE)

            val imageArea = preview.width.toDouble() * preview.height.toDouble()
            for (contour in contours) {
                val rect = Imgproc.boundingRect(contour)
                val area = rect.width.toDouble() * rect.height.toDouble()
                val aspect = rect.height.toDouble() / max(1, rect.width).toDouble()
                if (area > imageArea * 0.01 && area < imageArea * 0.15 && aspect in 0.80..1.20) {
                    candidates += Tile(rect.x, rect.y, rect.width, rect.height)
                }
            }
        } finally {
            contours.forEach { it.release() }
            hierarchy.release()
            binary.release()
            lines.release()
            edges.release()
            gray.release()
            sharpened.release()
            masked.release()
            mask.release()
            yellow.release()
            white.release()
            bgr.release()
            rgba.release()
            preview.recycle()
        }

        val tiles = suppressNestedDuplicates(candidates)
        require(tiles.isNotEmpty()) {
            "No clear character-card squares were detected. Try the manual crop tool for this screenshot."
        }
        require(tiles.size <= 100) {
            "The detector found too many regions (\${tiles.size}). Use manual crop review instead."
        }

        val decoder = BitmapRegionDecoder.newInstance(source.absolutePath, false)
            ?: error("Android could not open the original screenshot for cropping.")
        try {
            val outputDirectory = File(profileDirectory, "extracted_portraits").apply { mkdirs() }
            val scaleX = decoder.width.toFloat() / previewWidth
            val scaleY = decoder.height.toFloat() / previewHeight
            return tiles.map { tile ->
                val left = (tile.x * scaleX).toInt().coerceIn(0, decoder.width - 1)
                val top = (tile.y * scaleY).toInt().coerceIn(0, decoder.height - 1)
                val right = ((tile.x + tile.width) * scaleX).toInt().coerceIn(left + 1, decoder.width)
                val bottom = ((tile.y + tile.height) * scaleY).toInt().coerceIn(top + 1, decoder.height)
                var cropSample = 1
                while (max(right - left, bottom - top) / cropSample > 1400) cropSample *= 2
                val crop = decoder.decodeRegion(
                    Rect(left, top, right, bottom),
                    BitmapFactory.Options().apply {
                        inSampleSize = cropSample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                ) ?: error("Could not decode one detected card image.")
                try {
                    val name = "portrait_auto_\${UUID.randomUUID()}.png"
                    val destination = File(outputDirectory, name)
                    val temporary = File(outputDirectory, "\$name.tmp")
                    try {
                        FileOutputStream(temporary).use { output ->
                            check(crop.compress(Bitmap.CompressFormat.PNG, 100, output)) {
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

    private fun suppressNestedDuplicates(input: List<Tile>): List<Tile> {
        val kept = mutableListOf<Tile>()
        input.sortedByDescending { it.area }.forEach { candidate ->
            val duplicate = kept.any { existing ->
                val left = max(candidate.x, existing.x)
                val top = max(candidate.y, existing.y)
                val right = min(candidate.x + candidate.width, existing.x + existing.width)
                val bottom = min(candidate.y + candidate.height, existing.y + existing.height)
                val intersection = max(0, right - left) * max(0, bottom - top)
                val union = candidate.area + existing.area - intersection
                union > 0 && intersection.toDouble() / union > 0.80
            }
            if (!duplicate) kept += candidate
        }
        val sorted = kept.sortedBy { it.centerY }
        if (sorted.isEmpty()) return emptyList()
        val typicalHeight = sorted.map { it.height }.sorted()[sorted.size / 2].toFloat()
        val rows = mutableListOf<MutableList<Tile>>()
        sorted.forEach { tile ->
            val row = rows.lastOrNull()
            val rowCenter = row?.map { it.centerY }?.average()?.toFloat()
            if (row == null || rowCenter == null || abs(tile.centerY - rowCenter) > typicalHeight * 0.55f) {
                rows += mutableListOf(tile)
            } else {
                row += tile
            }
        }
        return rows.flatMap { row -> row.sortedBy { it.x } }
    }
}
