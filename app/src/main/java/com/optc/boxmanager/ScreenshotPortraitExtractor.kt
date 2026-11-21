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

/**
 * Saves a user-selected region of an imported screenshot as an offline PNG portrait.
 * The screenshot remains unchanged; character identity is assigned separately by the user.
 */
object ScreenshotPortraitExtractor {
    private fun decodeSelectedRegion(source: File, bounds: RectF): Bitmap {
        // Preferred path: decode only the selected region from the original full-resolution image.
        try {
            val decoder = BitmapRegionDecoder.newInstance(source.absolutePath, false)
            if (decoder != null) {
                try {
                    val left = (bounds.left * decoder.width).toInt().coerceIn(0, decoder.width - 1)
                    val top = (bounds.top * decoder.height).toInt().coerceIn(0, decoder.height - 1)
                    val right = (bounds.right * decoder.width).toInt().coerceIn(left + 1, decoder.width)
                    val bottom = (bounds.bottom * decoder.height).toInt().coerceIn(top + 1, decoder.height)
                    var sampleSize = 1
                    while (maxOf(right - left, bottom - top) / sampleSize > 1600) sampleSize *= 2
                    return decoder.decodeRegion(
                        Rect(left, top, right, bottom),
                        BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                    ) ?: error("Android could not decode the selected portrait area.")
                } finally {
                    decoder.recycle()
                }
            }
        } catch (error: OutOfMemoryError) {
            throw IllegalStateException(
                "Not enough memory to extract this crop. Select a smaller portrait area and try again.",
                error
            )
        } catch (_: Exception) {
            // Fall back for image codecs that do not support region decoding on this Android version.
        }

        // Compatibility path: decode a bounded-size preview, then crop in its scaled coordinates.
        val boundsOnly = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, boundsOnly)
        require(boundsOnly.outWidth > 0 && boundsOnly.outHeight > 0) {
            "Android could not read this screenshot image."
        }
        var sampleSize = 1
        while (maxOf(boundsOnly.outWidth, boundsOnly.outHeight) / sampleSize > 2048) sampleSize *= 2
        val full = try {
            BitmapFactory.decodeFile(
                source.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            )
        } catch (error: OutOfMemoryError) {
            throw IllegalStateException(
                "Not enough memory to read this screenshot. Close other apps and try again.",
                error
            )
        } ?: error("Android could not decode this screenshot image.")
        try {
            val left = (bounds.left * full.width).toInt().coerceIn(0, full.width - 1)
            val top = (bounds.top * full.height).toInt().coerceIn(0, full.height - 1)
            val right = (bounds.right * full.width).toInt().coerceIn(left + 1, full.width)
            val bottom = (bounds.bottom * full.height).toInt().coerceIn(top + 1, full.height)
            return Bitmap.createBitmap(full, left, top, right - left, bottom - top)
        } finally {
            full.recycle()
        }
    }

    fun extract(
        context: Context,
        profileId: String,
        screenshot: File,
        normalizedBounds: RectF,
        outputName: String = "portrait_${UUID.randomUUID()}.png"
    ): File {
        require(profileId.matches(Regex("[A-Za-z0-9-]{1,80}"))) {
            "Invalid box profile ID."
        }
        require(outputName.matches(Regex("portrait_[A-Za-z0-9_-]{1,80}\\.png"))) {
            "Invalid extracted portrait filename."
        }

        val profileDirectory = BoxStore(context).profileDirectory(profileId).canonicalFile
        val source = screenshot.canonicalFile
        require(source.isFile && source.path.startsWith(profileDirectory.path + File.separator)) {
            "The source screenshot must belong to the selected box profile."
        }
        require(source.length() in 1..(30L * 1024L * 1024L)) {
            "Screenshot is empty or exceeds the 30 MiB extraction limit."
        }
        require(
            normalizedBounds.left >= 0f && normalizedBounds.top >= 0f &&
            normalizedBounds.right <= 1f && normalizedBounds.bottom <= 1f &&
            normalizedBounds.width() > 0f && normalizedBounds.height() > 0f
        ) { "Crop bounds must be a non-empty normalized rectangle within the screenshot." }

        val crop = decodeSelectedRegion(source, normalizedBounds)
        try {
            val directory = File(profileDirectory, "extracted_portraits").apply { mkdirs() }
            val destination = File(directory, outputName)
            require(!destination.exists()) { "That extracted portrait filename already exists." }
            val temporary = File(directory, "$outputName.tmp")
            try {
                FileOutputStream(temporary).use { output ->
                    check(crop.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        "Could not encode the extracted portrait."
                    }
                    output.fd.sync()
                }
                check(temporary.renameTo(destination)) { "Could not save the extracted portrait." }
            } finally {
                if (temporary.exists()) temporary.delete()
            }
            return destination
        } finally {
            crop.recycle()
        }
    }
}
