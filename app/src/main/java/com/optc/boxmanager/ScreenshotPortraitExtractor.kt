package com.optc.boxmanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.BitmapRegionDecoder
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Produces offline portrait assets from screenshots already imported into a box profile.
 *
 * Card detection and portrait-bound selection are intentionally supplied by the caller:
 * the cropper never guesses character identity or silently chooses a crop region.
 */
object ScreenshotPortraitExtractor {
    /**
     * @param normalizedBounds portrait rectangle in screenshot coordinates, each edge 0f..1f.
     * @return PNG stored inside the selected profile's private extracted_portraits directory.
     */
    fun extract(
        context: Context,
        profileId: String,
        screenshot: File,
        normalizedBounds: RectF,
        outputName: String = "portrait_${UUID.randomUUID()}.png"
    ): File {
        require(profileId.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid box profile ID." }
        require(outputName.matches(Regex("portrait_[A-Za-z0-9_-]{1,80}\\.png"))) {
            "Invalid extracted portrait filename."
        }

        val profileDirectory = BoxStore(context).profileDirectory(profileId).canonicalFile
        val source = screenshot.canonicalFile
        require(source.isFile && source.path.startsWith(profileDirectory.path + File.separator)) {
            "The source screenshot must belong to the selected box profile."
        }
        require(
            normalizedBounds.left >= 0f && normalizedBounds.top >= 0f &&
            normalizedBounds.right <= 1f && normalizedBounds.bottom <= 1f &&
            normalizedBounds.width() > 0f && normalizedBounds.height() > 0f
        ) { "Crop bounds must be a non-empty normalized rectangle within the screenshot." }

        val decoder = BitmapRegionDecoder.newInstance(source.absolutePath, false)
            ?: error("Could not open the source screenshot for region decoding.")
        try {
            val left = (normalizedBounds.left * decoder.width).toInt().coerceIn(0, decoder.width - 1)
            val top = (normalizedBounds.top * decoder.height).toInt().coerceIn(0, decoder.height - 1)
            val right = (normalizedBounds.right * decoder.width).toInt().coerceIn(left + 1, decoder.width)
            val bottom = (normalizedBounds.bottom * decoder.height).toInt().coerceIn(top + 1, decoder.height)
            val crop = decoder.decodeRegion(
                Rect(left, top, right, bottom),
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            ) ?: error("Could not decode the selected portrait region.")
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
        } finally {
            decoder.recycle()
        }
    }
}
