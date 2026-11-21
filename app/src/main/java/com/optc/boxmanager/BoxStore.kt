package com.optc.boxmanager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.security.MessageDigest

data class BoxCharacter(
    val id: Int,
    val name: String,
    val quantity: Int = 1,
    val type: String = "",
    val classes: List<String> = emptyList(),
    val stars: String = "",
    val portraitFile: String? = null
)

data class BoxProfile(
    val id: String,
    val name: String,
    val server: String,
    val screenshots: List<String> = emptyList(),
    val characters: List<BoxCharacter> = emptyList()
)

class BoxStore(context: Context) {
    private val root = File(context.filesDir, "box_profiles").apply { mkdirs() }
    private val indexFile = File(root, "profiles.json")

    fun load(): List<BoxProfile> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(indexFile.readText())
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                val shots = item.optJSONArray("screenshots") ?: JSONArray()
                val characterArray = item.optJSONArray("characters") ?: JSONArray()
                val characters = (0 until characterArray.length()).mapNotNull { index ->
                    val character = characterArray.optJSONObject(index) ?: return@mapNotNull null
                    val classArray = character.optJSONArray("classes") ?: JSONArray()
                    BoxCharacter(
                        id = character.optInt("characterId", -1),
                        name = character.optString("name", "Unknown"),
                        quantity = character.optInt("quantity", 1).coerceAtLeast(1),
                        type = character.optString("type", ""),
                        classes = (0 until classArray.length()).map { classArray.optString(it) },
                        stars = character.optString("stars", ""),
                        portraitFile = character.optString("portraitFile").takeIf { it.isNotBlank() && it != "null" }
                    ).takeIf { it.id > 0 }
                }
                BoxProfile(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    server = item.optString("server", "Global"),
                    screenshots = (0 until shots.length()).map { shots.getString(it) },
                    characters = characters
                )
            }
        }.getOrDefault(emptyList())
    }

    fun create(name: String, server: String): BoxProfile {
        val profile = BoxProfile(UUID.randomUUID().toString(), name.trim(), server)
        save(load() + profile)
        File(root, profile.id).mkdirs()
        return profile
    }

    fun addCharacter(profileId: String, reference: CharacterReference, portraitFileName: String? = null) {
        val profiles = load()
        val profile = profiles.firstOrNull { it.id == profileId } ?: error("Box profile no longer exists.")
        val verifiedPortrait = portraitFileName?.let { name ->
            require(name.matches(Regex("portrait_[A-Za-z0-9_-]{1,80}\\.(?:png|jpe?g)"))) {
                "Invalid portrait crop filename."
            }
            val portraitDirectory = File(profileDirectory(profileId), "extracted_portraits").canonicalFile
            val portrait = File(portraitDirectory, name).canonicalFile
            require(portrait.isFile && portrait.path.startsWith(portraitDirectory.path + File.separator)) {
                "The selected crop does not belong to this box profile."
            }
            name
        }
        val existing = profile.characters.firstOrNull { it.id == reference.id }
        val updated = if (existing == null) {
            profile.characters + BoxCharacter(
                id = reference.id,
                name = reference.name,
                quantity = 1,
                type = reference.type,
                classes = reference.classes,
                stars = reference.stars,
                portraitFile = verifiedPortrait
            )
        } else {
            profile.characters.map {
                if (it.id == reference.id) it.copy(
                    quantity = it.quantity + 1,
                    name = reference.name,
                    type = reference.type,
                    classes = reference.classes,
                    stars = reference.stars,
                    portraitFile = verifiedPortrait ?: it.portraitFile
                ) else it
            }
        }
        save(profiles.map { if (it.id == profileId) it.copy(characters = updated) else it })
    }

    fun addScreenshots(profileId: String, files: List<File>): Int {
        val directory = profileDirectory(profileId)
        val previousNames = load().firstOrNull { it.id == profileId }?.screenshots.orEmpty()
        val knownHashes = previousNames
            .mapNotNull { name -> runCatching { sha256(File(directory, name)) }.getOrNull() }
            .toMutableSet()
        val accepted = files.filter { file ->
            val hash = runCatching { sha256(file) }.getOrNull()
            if (hash == null || !knownHashes.add(hash)) {
                file.delete()
                false
            } else true
        }
        if (accepted.isNotEmpty()) {
            val profiles = load().map { profile ->
                if (profile.id != profileId) profile else
                    profile.copy(screenshots = profile.screenshots + accepted.map { it.name })
            }
            save(profiles)
        }
        return accepted.size
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun profileDirectory(profileId: String): File {
        require(profileId.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid box profile ID." }
        return File(root, profileId).apply { mkdirs() }
    }

    fun extractedPortraits(profileId: String): List<File> {
        val directory = File(profileDirectory(profileId), "extracted_portraits")
        return directory.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg") }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    fun exportJson(profile: BoxProfile): String {
        val screenshots = JSONArray().apply { profile.screenshots.forEach { put(it) } }
        val characters = JSONArray().apply {
            profile.characters.sortedBy { it.id }.forEach { character ->
                put(JSONObject().apply {
                    put("characterId", character.id)
                    put("name", character.name)
                    put("quantity", character.quantity)
                    put("type", character.type)
                    put("classes", JSONArray(character.classes))
                    put("stars", character.stars)
                    // Portrait files are omitted until export bundles include the image assets.
                })
            }
        }
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("application", "OPTC Box Manager")
            put("profile", JSONObject().apply {
                put("name", profile.name)
                put("server", profile.server)
            })
            put("characters", characters)
            put("sourceScreenshots", screenshots)
            if (profile.characters.isEmpty()) {
                put("notes", "Character recognition has not yet populated this collection.")
            }
        }.toString(2)
    }

    private fun save(profiles: List<BoxProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("server", profile.server)
                put("screenshots", JSONArray(profile.screenshots))
                put("characters", JSONArray().apply {
                    profile.characters.forEach { character ->
                        put(JSONObject().apply {
                            put("characterId", character.id)
                            put("name", character.name)
                            put("quantity", character.quantity)
                            put("type", character.type)
                            put("classes", JSONArray(character.classes))
                            put("stars", character.stars)
                            put("portraitFile", character.portraitFile)
                        })
                    }
                })
            })
        }
        val temporary = File(root, "profiles.json.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(array.toString(2).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), indexFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}
