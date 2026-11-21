package com.optc.boxmanager

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Offline portrait cache.
 *
 * Every network fetch must receive headers from the currently authenticated
 * game API session. The X-SESSION value is required and is never persisted by
 * this cache. Call from a background dispatcher/thread.
 */
object PortraitCache {
    private const val GLOBAL_BASE =
        "https://cdn.gb.onepiece-tc.jp/en/images/characters/character_"
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

    fun cachedFile(context: Context, server: String, characterId: Int): File =
        File(File(context.filesDir, "portraits/${server.lowercase()}"), "$characterId.png")

    fun getOrDownload(
        context: Context,
        server: String,
        characterId: Int,
        authenticatedSessionHeaders: Map<String, String>
    ): File {
        require(characterId > 0) { "Character ID must be positive." }
        require(server == "Global") {
            "The Japan portrait CDN endpoint has not been verified yet."
        }

        val sessionId = authenticatedSessionHeaders.entries
            .firstOrNull { it.key.equals("X-SESSION", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?: error("Portrait download requires an active authenticated game session (X-SESSION).")

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
            connection.setRequestProperty("X-SESSION", sessionId)

            // Carry over the game client's identifying/auth headers only.
            // Never copy the app API Host header to the CDN host.
            authenticatedSessionHeaders.forEach { (name, value) ->
                if (
                    name.equals("User-Agent", ignoreCase = true) ||
                    name.equals("Authorization", ignoreCase = true)
                ) {
                    connection.setRequestProperty(name, value)
                }
            }

            connection.connect()
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Authenticated portrait request returned HTTP ${connection.responseCode}."
            }
            check(connection.contentType?.startsWith("image/") == true) {
                "Authenticated portrait request did not return an image."
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
