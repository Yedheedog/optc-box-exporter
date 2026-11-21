package com.optc.boxmanager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf(store.load()) }
    var selectedId by remember { mutableStateOf(profiles.firstOrNull()?.id) }
    var showCreate by remember { mutableStateOf(false) }
    var showScreenshotPicker by remember { mutableStateOf(false) }
    var cropScreenshot by remember { mutableStateOf<File?>(null) }
    var cropMessage by remember { mutableStateOf<String?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var authStatus by remember { mutableStateOf(if (AuthProfileVault.hasProfile(context)) "Encrypted auth source is stored on this device." else "No auth source imported.") }
    val selected = profiles.firstOrNull { it.id == selectedId }

    val authImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching { AuthProfileVault.storeFromUri(context, uri) }
                .onSuccess { authStatus = "Encrypted auth source stored separately from box profiles. Login adapter is not connected yet." }
                .onFailure { authStatus = "Import failed: ${it.localizedMessage}" }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val profile = profiles.firstOrNull { it.id == selectedId } ?: return@rememberLauncherForActivityResult
        val copied = mutableListOf<File>()
        uris.forEach { uri ->
            runCatching {
                val extension = context.contentResolver.getType(uri)
                    ?.substringAfter('/', "jpg")
                    ?.let { if (it == "jpeg") "jpg" else it } ?: "jpg"
                val destination = File(
                    store.profileDirectory(profile.id),
                    "screenshot_${java.util.UUID.randomUUID()}.$extension"
                )
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
                if (destination.exists() && destination.length() > 0) copied += destination
            }
        }
        if (copied.isNotEmpty()) {
            val accepted = store.addScreenshots(profile.id, copied)
            profiles = store.load()
            importMessage = "Added $accepted unique screenshot(s); exact duplicates were skipped."
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
                } ?: error("Could not open the destination file.")
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

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Portrait-download authentication", style = MaterialTheme.typography.titleMedium)
                    Text("Optional Farm Lite device profile. It is encrypted with Android Keystore and kept outside all box profiles.")
                    OutlinedButton(onClick = { authImporter.launch(arrayOf("*/*")) }) {
                        Text("Import auth source file")
                    }
                    Text(authStatus)
                }
            }

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
                val savedCrops = store.extractedPortraits(selected.id)
                Text("Selected: ${selected.name}", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = { imagePicker.launch(arrayOf("image/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add screenshots from gallery") }
                OutlinedButton(
                    enabled = selected.screenshots.isNotEmpty(),
                    onClick = { showScreenshotPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Extract portrait from screenshot") }
                Text("Saved offline screenshot portraits: ${savedCrops.size}")
                if (savedCrops.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(savedCrops, key = { it.name }) { crop ->
                            CropThumbnail(crop)
                        }
                    }
                }
                OutlinedButton(
                    onClick = { jsonExporter.launch("${selected.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}_box.json") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Export collection as JSON") }
                Text("Recognition status: not connected yet")
                Text("Screenshots and extracted portraits stay inside this profile's private app folder.")
            }
            importMessage?.let { Text(it) }
            cropMessage?.let { Text(it) }
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

    if (showScreenshotPicker && selected != null) {
        AlertDialog(
            onDismissRequest = { showScreenshotPicker = false },
            title = { Text("Choose a source screenshot") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(selected.screenshots) { name ->
                        TextButton(
                            onClick = {
                                cropScreenshot = File(store.profileDirectory(selected.id), name)
                                showScreenshotPicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(name) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showScreenshotPicker = false }) { Text("Close") }
            }
        )
    }

    cropScreenshot?.let { screenshot ->
        CropReviewDialog(
            screenshot = screenshot,
            onDismiss = { cropScreenshot = null },
            onSave = { bounds ->
                val profileId = selectedId ?: return@CropReviewDialog
                cropScreenshot = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            ScreenshotPortraitExtractor.extract(context, profileId, screenshot, bounds)
                        }
                    }
                    result.onSuccess {
                        cropMessage = "Portrait crop saved offline: ${it.name}"
                    }.onFailure {
                        cropMessage = "Could not extract portrait: ${it.localizedMessage}"
                    }
                }
            }
        )
    }
}


@Composable
private fun CropThumbnail(file: File) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = file.absolutePath) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath) }
    }
    DisposableEffect(bitmap) {
        onDispose { bitmap?.takeIf { !it.isRecycled }?.recycle() }
    }
    Card {
        Column(
            Modifier.padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "Extracted offline portrait",
                    modifier = Modifier.size(92.dp),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                    Text("Loading")
                }
            }
            Text(file.name, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CropReviewDialog(
    screenshot: File,
    onDismiss: () -> Unit,
    onSave: (RectF) -> Unit
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = screenshot.absolutePath) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(screenshot.absolutePath) }
    }
    DisposableEffect(bitmap) {
        onDispose { bitmap?.takeIf { !it.isRecycled }?.recycle() }
    }
    val imageBitmap = remember(bitmap) { bitmap?.asImageBitmap() }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }

    fun fittedImageRect(size: IntSize): Rect {
        if (size.width <= 0 || size.height <= 0 || bitmap == null) return Rect.Zero
        val scale = min(
            size.width.toFloat() / bitmap.width.toFloat(),
            size.height.toFloat() / bitmap.height.toFloat()
        )
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = (size.width - width) / 2f
        val top = (size.height - height) / 2f
        return Rect(left, top, left + width, top + height)
    }

    val imageRect = fittedImageRect(containerSize)
    val selection = if (dragStart != null && dragEnd != null && imageRect.width > 0f && imageRect.height > 0f) {
        val left = max(imageRect.left, min(dragStart!!.x, dragEnd!!.x))
        val top = max(imageRect.top, min(dragStart!!.y, dragEnd!!.y))
        val right = min(imageRect.right, max(dragStart!!.x, dragEnd!!.x))
        val bottom = min(imageRect.bottom, max(dragStart!!.y, dragEnd!!.y))
        if (right > left && bottom > top) Rect(left, top, right, bottom) else null
    } else null

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Select portrait area", style = MaterialTheme.typography.titleLarge)
                Text("Drag over the character portrait. The original screenshot will remain unchanged.")
                if (imageBitmap != null) {
                    Box(
                        Modifier.fillMaxWidth()
                            .height(430.dp)
                            .onSizeChanged { containerSize = it }
                            .pointerInput(imageBitmap, containerSize) {
                                detectDragGestures(
                                    onDragStart = { point ->
                                        val bounds = fittedImageRect(containerSize)
                                        if (point.x in bounds.left..bounds.right && point.y in bounds.top..bounds.bottom) {
                                            dragStart = point
                                            dragEnd = point
                                        } else {
                                            dragStart = null
                                            dragEnd = null
                                        }
                                    },
                                    onDragEnd = {},
                                    onDragCancel = { dragStart = null; dragEnd = null }
                                ) { change, _ ->
                                    if (dragStart != null) {
                                        dragEnd = change.position
                                        change.consume()
                                    }
                                }
                            }
                    ) {
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = "Source OPTC box screenshot",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                        Canvas(Modifier.fillMaxSize()) {
                            selection?.let { rect ->
                                drawRect(
                                    color = Color.Yellow.copy(alpha = 0.20f),
                                    topLeft = Offset(rect.left, rect.top),
                                    size = androidx.compose.ui.geometry.Size(rect.width, rect.height)
                                )
                                drawRect(
                                    color = Color.Yellow,
                                    topLeft = Offset(rect.left, rect.top),
                                    size = androidx.compose.ui.geometry.Size(rect.width, rect.height),
                                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                                )
                            }
                        }
                    }
                } else {
                    Text("This screenshot could not be decoded.")
                }
                Text("Crop only the character artwork; exclude level text, buttons, and neighboring cards.")
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        enabled = selection != null && bitmap != null,
                        onClick = {
                            val rect = selection ?: return@Button
                            val bounds = imageRect
                            val normalized = RectF(
                                ((rect.left - bounds.left) / bounds.width).coerceIn(0f, 1f),
                                ((rect.top - bounds.top) / bounds.height).coerceIn(0f, 1f),
                                ((rect.right - bounds.left) / bounds.width).coerceIn(0f, 1f),
                                ((rect.bottom - bounds.top) / bounds.height).coerceIn(0f, 1f)
                            )
                            onSave(normalized)
                        }
                    ) { Text("Save crop") }
                }
            }
        }
    }
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
