package com.optc.boxmanager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

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

    fun addScreenshots(profileId: String, files: List<File>) {
        val profiles = load().map { profile ->
            if (profile.id != profileId) profile else
                profile.copy(screenshots = profile.screenshots + files.map { it.name })
        }
        save(profiles)
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
