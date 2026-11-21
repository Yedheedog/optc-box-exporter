package com.optc.boxmanager

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class CharacterReference(
    val id: Int,
    val name: String,
    val type: String,
    val classes: List<String>,
    val rarity: Int,
    val stars: String
)

/**
 * One shared, static character catalogue for every local box profile.
 * It accepts only the documented static-reference JSON shape; account inventory
 * and profile/save data are neither read nor stored here.
 */
class CharacterReferenceStore(context: Context) {
    private val directory = File(context.filesDir, "shared_character_reference").apply { mkdirs() }
    private val referenceFile = File(directory, "characters.json")

    fun count(): Int = runCatching { load().size }.getOrDefault(0)

    fun load(): List<CharacterReference> {
        if (!referenceFile.isFile) return emptyList()
        val root = JSONObject(referenceFile.readText(Charsets.UTF_8))
        return parse(root)
    }

    fun importFromUri(context: Context, uri: Uri): Int {
        val raw = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_REFERENCE_BYTES) { "Reference file exceeds the 32 MiB limit." }
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name())
        } ?: error("Could not open the selected reference file.")

        val root = JSONObject(raw)
        val records = parse(root)
        require(records.isNotEmpty()) { "The character reference contains no usable records." }

        val canonical = JSONObject().apply {
            put("schema_version", 1)
            put("source", root.optString("source", "Imported static OPTC character reference"))
            put("character_count", records.size)
            put("characters", JSONArray().apply {
                records.forEach { character ->
                    put(JSONObject().apply {
                        put("id", character.id)
                        put("name", character.name)
                        put("type", character.type)
                        put("classes", JSONArray(character.classes))
                        put("rarity", character.rarity)
                        put("stars", character.stars)
                    })
                }
            })
        }.toString(2)

        val temporary = File(directory, "characters.json.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(canonical.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    referenceFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    referenceFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        return records.size
    }

    fun search(query: String, limit: Int = 100): List<CharacterReference> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return load().asSequence()
            .filter {
                it.id.toString().contains(needle) ||
                    it.name.contains(needle, ignoreCase = true)
            }
            .take(limit.coerceIn(1, 500))
            .toList()
    }

    private fun parse(root: JSONObject): List<CharacterReference> {
        require(root.optInt("schema_version", -1) == 1) {
            "Unsupported character-reference schema version."
        }
        val array = root.optJSONArray("characters")
            ?: error("The reference JSON has no characters array.")
        val declaredCount = root.optInt("character_count", array.length())
        require(declaredCount == array.length()) { "Character count does not match the records in the file." }
        val seen = HashSet<Int>(array.length())
        return (0 until array.length()).map { index ->
            val item = array.optJSONObject(index)
                ?: error("Invalid character record at index $index.")
            val id = item.optInt("id", -1)
            require(id > 0) { "Character IDs must be positive integers." }
            require(seen.add(id)) { "Duplicate character ID $id in reference file." }
            val name = item.optString("name").trim()
            require(name.isNotEmpty()) { "Character $id has no name." }
            val classArray = item.optJSONArray("classes") ?: JSONArray()
            val classes = (0 until classArray.length())
                .mapNotNull { classArray.optString(it).takeIf(String::isNotBlank) }
            CharacterReference(
                id = id,
                name = name,
                type = item.optString("type", ""),
                classes = classes,
                rarity = item.optInt("rarity", 0).coerceAtLeast(0),
                stars = item.optString("stars", "")
            )
        }
    }

    companion object {
        private const val MAX_REFERENCE_BYTES = 32 * 1024 * 1024
    }
}
