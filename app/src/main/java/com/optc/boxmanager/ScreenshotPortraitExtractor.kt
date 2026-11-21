package com.optc.boxmanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
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

        val bitmap = BitmapFactory.decodeFile(source.absolutePath)
            ?: error("Could not decode the source screenshot.")
        try {
            val left = (normalizedBounds.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
            val top = (normalizedBounds.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            val right = (normalizedBounds.right * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
            val bottom = (normalizedBounds.bottom * bitmap.height).toInt().coIn(top + 1, bitmap.height)
            val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
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
            bitmap.recycle()
        }
    }
}
