package com.optc.boxmanager

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { BoxManagerScreen() } }
    }
}

@Composable
private fun BoxManagerScreen() {
    val context = LocalContext.current
    val store = remember { BoxStore(context) }
    var profiles by remember { mutableStateOf(store.load()) }
    var selectedId by remember { mutableStateOf(profiles.firstOrNull()?.id) }
    var showCreate by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val selected = profiles.firstOrNull { it.id == selectedId }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val profile = profiles.firstOrNull { it.id == selectedId } ?: return@rememberLauncherForActivityResult
        val copied = mutableListOf<File>()
        uris.forEachIndexed { index, uri ->
            runCatching {
                val extension = context.contentResolver.getType(uri)
                    ?.substringAfter('/', "jpg")
                    ?.let { if (it == "jpeg") "jpg" else it } ?: "jpg"
                val destination = File(
                    store.profileDirectory(profile.id),
                    "screenshot_${System.currentTimeMillis()}_${index}.$extension"
                )
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
                if (destination.exists() && destination.length() > 0) copied += destination
            }
        }
        if (copied.isNotEmpty()) {
            store.addScreenshots(profile.id, copied)
            profiles = store.load()
        }
    }

    val jsonExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val profile = profiles.firstOrNull { it.id == selectedId }
        if (uri != null && profile != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(store.exportJson(profile))
                }
            }.onSuccess { exportMessage = "JSON file saved." }
                .onFailure { exportMessage = "Could not write JSON: ${it.localizedMessage}" }
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("OPTC Box Manager", style = MaterialTheme.typography.headlineMedium)
            Text("Independent local box profiles • Android")
            Button(onClick = { showCreate = true }) { Text("Add box profile") }

            if (profiles.isEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("No box profiles yet")
                        Text("Create a named profile for each game account. These profiles do not log in to or change your game accounts.")
                    }
                }
            } else {
                Text("Your box profiles", style = MaterialTheme.typography.titleMedium)
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(profiles, key = { it.id }) { profile ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedId == profile.id,
                                    onClick = { selectedId = profile.id }
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                                    Text("${profile.server} • ${profile.screenshots.size} screenshots")
                                }
                            }
                        }
                    }
                }
            }

            if (selected != null) {
                Text("Selected: ${selected.name}", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = { imagePicker.launch(arrayOf("image/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add screenshots from gallery") }
                OutlinedButton(
                    onClick = { jsonExporter.launch("${selected.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}_box.json") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Export collection as JSON") }
                Text("Recognition status: not connected yet")
                Text("Screenshots are stored only inside this profile's private app folder.")
            }
            exportMessage?.let { Text(it) }
            Spacer(Modifier.height(4.dp))
        }
    }

    if (showCreate) CreateProfileDialog(
        onDismiss = { showCreate = false },
        onCreate = { name, server ->
            val created = store.create(name, server)
            profiles = store.load()
            selectedId = created.id
            showCreate = false
        }
    )
}

@Composable
private fun CreateProfileDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("Global") }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create box profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile name") },
                    singleLine = true
                )
                OutlinedButton(onClick = { expanded = true }) { Text("Server: $server") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    listOf("Global", "Japan").forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = { server = option; expanded = false }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim(), server) }) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
