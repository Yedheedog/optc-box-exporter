package com.optc.boxmanager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.security.MessageDigest

data class BoxProfile(
    val id: String,
    val name: String,
    val server: String,
    val screenshots: List<String> = emptyList()
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
                BoxProfile(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    server = item.optString("server", "Global"),
                    screenshots = (0 until shots.length()).map { shots.getString(it) }
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

    fun addScreenshots(profileId: String, files: List<File>): Int {
        val directory = profileDirectory(profileId)
        val knownHashes = directory.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            ?.mapNotNull { runCatching { sha256(it) }.getOrNull() }
            ?.toMutableSet() ?: mutableSetOf()
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

    fun profileDirectory(profileId: String) = File(root, profileId).apply { mkdirs() }

    fun exportJson(profile: BoxProfile): String {
        val screenshots = JSONArray().apply { profile.screenshots.forEach { put(it) } }
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("application", "OPTC Box Manager")
            put("profile", JSONObject().apply {
                put("name", profile.name)
                put("server", profile.server)
            })
            put("characters", JSONArray())
            put("sourceScreenshots", screenshots)
            put("notes", "Character recognition has not yet populated this collection.")
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
            })
        }
        indexFile.writeText(array.toString(2))
    }
}
