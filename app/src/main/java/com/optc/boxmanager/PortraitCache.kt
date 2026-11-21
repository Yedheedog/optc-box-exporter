package com.optc.boxmanager

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Offline portrait cache. Call getOrDownload from a background dispatcher/thread.
 * Global URL pattern is copied from Farm Lite's get_card_url helper.
 * Japan is deliberately rejected until its CDN endpoint is verified.
 */
object PortraitCache {
    private const val GLOBAL_BASE =
        "https://cdn.gb.onepiece-tc.jp/en/images/characters/character_"
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

    fun cachedFile(context: Context, server: String, characterId: Int): File =
        File(File(context.filesDir, "portraits/${server.lowercase()}"), "$characterId.png")

    fun getOrDownload(context: Context, server: String, characterId: Int): File {
        require(characterId > 0) { "Character ID must be positive." }
        require(server == "Global") {
            "The Japan portrait CDN endpoint has not been verified yet."
        }

        val destination = cachedFile(context, server, characterId)
        if (destination.exists() && destination.length() > 0 &&
            BitmapFactory.decodeFile(destination.absolutePath) != null
        ) return destination

        destination.parentFile?.mkdirs()
        val paddedId = characterId.toString().padStart(4, '0')
        val connection = (URL("$GLOBAL_BASE${paddedId}_t.png").openConnection() as HttpURLConnection)
        val temporary = File(destination.parentFile, "${destination.name}.download")
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "image/png,image/*;q=0.9")
            connection.connect()

            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Portrait server returned HTTP ${connection.responseCode}."
            }
            check(connection.contentType?.startsWith("image/") == true) {
                "Portrait server did not return an image."
            }

            var total = 0
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= MAX_IMAGE_BYTES) { "Portrait image exceeded the size limit." }
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(total > 0 && BitmapFactory.decodeFile(temporary.absolutePath) != null) {
                "Downloaded portrait was empty or invalid."
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
            return destination
        } finally {
            connection.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }
}
