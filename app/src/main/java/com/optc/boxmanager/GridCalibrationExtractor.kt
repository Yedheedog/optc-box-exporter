package com.optc.boxmanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.graphics.RectF
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Calibrated OPTC box grid cropper.
 * The user selects the full five-column grid and one complete card tile.
 * The selected tile calibrates row height; the known OPTC five-column layout
 * determines column pitch. Every crop is a complete card tile for review.
 */
object GridCalibrationExtractor {
    private const val COLUMN_COUNT = 5
    private const val MAX_TILES = 100

    fun extract(
        context: Context,
        profileId: String,
        screenshot: File,
        gridBounds: RectF,
        sampleTileBounds: RectF
    ): List<File> {
        require(profileId.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid box profile ID." }
        val profileDirectory = BoxStore(context).profileDirectory(profileId).canonicalFile
        val source = screenshot.canonicalFile
        require(source.isFile && source.path.startsWith(profileDirectory.path + File.separator)) {
            "The source screenshot must belong to the selected box profile."
        }
        require(source.length() in 1..(30L * 1024L * 1024L)) {
            "Screenshot is empty or exceeds the 30 MiB limit."
        }
        fun valid(rect: RectF) = rect.left >= 0f && rect.top >= 0f &&
            rect.right <= 1f && rect.bottom <= 1f &&
            rect.width() > 0.005f && rect.height() > 0.005f
        require(valid(gridBounds) && valid(sampleTileBounds)) {
            "The grid or sample-card selection is too small or outside the screenshot."
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        val imageWidth = bounds.outWidth
        val imageHeight = bounds.outHeight
        require(imageWidth > 0 && imageHeight > 0) { "This screenshot image could not be decoded." }

        val gridLeft = (gridBounds.left * imageWidth).roundToInt().coerceIn(0, imageWidth - 1)
        val gridTop = (gridBounds.top * imageHeight).roundToInt().coerceIn(0, imageHeight - 1)
        val gridRight = (gridBounds.right * imageWidth).roundToInt().coerceIn(gridLeft + 1, imageWidth)
        val gridBottom = (gridBounds.bottom * imageHeight).roundToInt().coerceIn(gridTop + 1, imageHeight)
        val sampleLeft = (sampleTileBounds.left * imageWidth).roundToInt()
        val sampleTop = (sampleTileBounds.top * imageHeight).roundToInt()
        val sampleRight = (sampleTileBounds.right * imageWidth).roundToInt()
        val sampleBottom = (sampleTileBounds.bottom * imageHeight).roundToInt()
        require(sampleLeft >= gridLeft && sampleTop >= gridTop &&
            sampleRight <= gridRight && sampleBottom <= gridBottom) {
            "The sample card must be fully inside the selected grid. Go back and select the grid again."
        }

        val gridWidth = gridRight - gridLeft
        val gridHeight = gridBottom - gridTop
        val cellWidth = gridWidth.toFloat() / COLUMN_COUNT
        val sampleWidth = (sampleRight - sampleLeft).toFloat()
        val sampleHeight = (sampleBottom - sampleTop).toFloat()
        val widthRatio = sampleWidth / cellWidth
        require(widthRatio in 0.65f..1.35f) {
            "The sample card width does not match a five-column grid. Select the grid tightly, then select one complete card."
        }

        val rowCount = (gridHeight / sampleHeight).roundToInt().coerceAtLeast(1)
        require(rowCount * COLUMN_COUNT <= MAX_TILES) {
            "The selected grid would produce more than $MAX_TILES crops. Select only the visible character grid."
        }
        val cellHeight = gridHeight.toFloat() / rowCount
        val heightRatio = sampleHeight / cellHeight
        require(heightRatio in 0.65f..1.35f) {
            "The sample card height does not match the selected grid. Select one complete card tile and exclude surrounding space."
        }

        val decoder = BitmapRegionDecoder.newInstance(source.absolutePath, false)
            ?: error("Android could not open the original screenshot for cropping.")
        try {
            val outputDirectory = File(profileDirectory, "extracted_portraits").apply { mkdirs() }
            val result = mutableListOf<File>()
            for (row in 0 until rowCount) {
                for (column in 0 until COLUMN_COUNT) {
                    val left = (gridLeft + column * cellWidth).roundToInt().coerceIn(0, imageWidth - 1)
                    val top = (gridTop + row * cellHeight).roundToInt().coerceIn(0, imageHeight - 1)
                    val right = (gridLeft + (column + 1) * cellWidth).roundToInt().coerceIn(left + 1, imageWidth)
                    val bottom = (gridTop + (row + 1) * cellHeight).roundToInt().coerceIn(top + 1, imageHeight)
                    val crop = decoder.decodeRegion(
                        Rect(left, top, right, bottom),
                        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                    ) ?: error("Could not decode card at row ${row + 1}, column ${column + 1}.")
                    try {
                        val name = "portrait_grid_r${row + 1}_c${column + 1}_${UUID.randomUUID()}.jpg"
                        val destination = File(outputDirectory, name)
                        val temporary = File(outputDirectory, "$name.tmp")
                        try {
                            FileOutputStream(temporary).use { output ->
                                check(crop.compress(Bitmap.CompressFormat.JPEG, 94, output)) {
                                    "Could not encode card at row ${row + 1}, column ${column + 1}."
                                }
                                output.fd.sync()
                            }
                            check(temporary.renameTo(destination)) { "Could not save a detected card image." }
                        } finally {
                            if (temporary.exists()) temporary.delete()
                        }
                        result += destination
                    } finally {
                        crop.recycle()
                    }
                }
            }
            return result
        } finally {
            decoder.recycle()
        }
    }
}
