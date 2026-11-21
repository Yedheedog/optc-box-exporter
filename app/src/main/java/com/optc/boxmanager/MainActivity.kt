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
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.key
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
    val referenceStore = remember { CharacterReferenceStore(context) }
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf(store.load()) }
    var selectedId by remember { mutableStateOf(profiles.firstOrNull()?.id) }
    var showCreate by remember { mutableStateOf(false) }
    var showScreenshotPicker by remember { mutableStateOf(false) }
    var showAutoExtractPicker by remember { mutableStateOf(false) }
    var showAddCharacter by remember { mutableStateOf(false) }
    var cropScreenshot by remember { mutableStateOf<File?>(null) }
    var gridCalibrationScreenshot by remember { mutableStateOf<File?>(null) }
    var gridCalibrationStage by remember { mutableIntStateOf(0) }
    var calibratedGridBounds by remember { mutableStateOf<RectF?>(null) }
    var cropMessage by remember { mutableStateOf<String?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    var authStatus by remember { mutableStateOf(if (AuthProfileVault.hasProfile(context)) "Encrypted auth source is stored on this device." else "No auth source imported.") }
    var referenceCount by remember { mutableIntStateOf(referenceStore.count()) }
    var referenceStatus by remember { mutableStateOf(if (referenceCount > 0) "Shared character catalogue loaded." else "No character catalogue imported.") }
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

    val referenceImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching { referenceStore.importFromUri(context, uri) }
                .onSuccess { count ->
                    referenceCount = count
                    referenceStatus = "Imported $count static character records. This catalogue is shared by all box profiles."
                }
                .onFailure { referenceStatus = "Reference import failed: ${it.localizedMessage}" }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val profile = profiles.firstOrNull { it.id == selectedId } ?: return@rememberLauncherForActivityResult
        importMessage = "Importing and validating screenshots…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val copied = mutableListOf<File>()
                    var rejected = 0
                    uris.forEach { uri ->
                        val mime = context.contentResolver.getType(uri)
                        val extension = when (mime?.lowercase()) {
                            "image/jpeg", "image/jpg" -> "jpg"
                            "image/png" -> "png"
                            "image/webp" -> "webp"
                            else -> null
                        }
                        if (extension == null) {
                            rejected++
                            return@forEach
                        }
                        val destination = File(
                            store.profileDirectory(profile.id),
                            "screenshot_${java.util.UUID.randomUUID()}.$extension"
                        )
                        try {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                destination.outputStream().use { output ->
                                    val buffer = ByteArray(16 * 1024)
                                    var total = 0L
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        total += count
                                        require(total <= 30L * 1024L * 1024L) {
                                            "Screenshot exceeds the 30 MiB size limit."
                                        }
                                        output.write(buffer, 0, count)
                                    }
                                }
                            } ?: error("Could not open selected image.")
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(destination.absolutePath, bounds)
                            require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                                "Selected file is not a supported image."
                            }
                            copied += destination
                        } catch (_: Exception) {
                            destination.delete()
                            rejected++
                        }
                    }
                    val accepted = store.addScreenshots(profile.id, copied)
                    accepted to rejected
                }
            }
            result.onSuccess { (accepted, rejected) ->
                profiles = store.load()
                importMessage = "Added $accepted unique screenshot(s); exact duplicates were skipped. Rejected $rejected unsupported or invalid file(s)."
            }.onFailure {
                importMessage = "Screenshot import failed: ${it.localizedMessage}"
            }
        }
    }

    val jsonExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val json = pendingExportJson
        pendingExportJson = null
        if (uri == null) {
            exportMessage = "Export cancelled."
        } else if (json == null) {
            exportMessage = "Export data was unavailable. Please try again."
        } else {
            exportMessage = "Saving JSON…"
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                            output.write(json.toByteArray(Charsets.UTF_8))
                            output.flush()
                        } ?: error("Android could not open the selected destination.")
                    }
                }
                result.onSuccess { exportMessage = "JSON file saved successfully." }
                    .onFailure { exportMessage = "JSON export failed: ${it.localizedMessage ?: it.javaClass.simpleName}" }
            }
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("OPTC Box Manager", style = MaterialTheme.typography.headlineMedium)
            Text("Independent local box profiles • Android")
            Button(onClick = { showCreate = true }) { Text("Add box profile") }
            Button(
                onClick = { imagePicker.launch(arrayOf("image/*")) },
                enabled = selected != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Upload screenshots from gallery") }
            if (selected == null) {
                Text("Create or select a box profile before uploading screenshots.")
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Shared character reference", style = MaterialTheme.typography.titleMedium)
                    Text("Import a static character catalogue JSON exported from the Farm Lite MstCharacter_ master table. It is shared across profiles and contains no box inventory.")
                    OutlinedButton(onClick = { referenceImporter.launch(arrayOf("application/json", "text/json", "*/*")) }) {
                        Text("Import character reference JSON")
                    }
                    Text("$referenceCount character records • $referenceStatus")
                }
            }

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
                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
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
                OutlinedButton(
                    enabled = selected.screenshots.isNotEmpty(),
                    onClick = {
                        cropMessage = null
                        showScreenshotPicker = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Crop portrait from imported screenshot") }
                Text(
                    "Choose one of the screenshots already imported into this profile. This action does not open a file manager.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    enabled = selected.screenshots.isNotEmpty(),
                    onClick = { showAutoExtractPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Auto-extract card images") }
                Text("Saved offline screenshot portraits: ${savedCrops.size}")
                if (referenceCount > 0) {
                    OutlinedButton(onClick = { showAddCharacter = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Add character from reference catalogue")
                    }
                }
                Text("Collection (${selected.characters.size} character IDs)", style = MaterialTheme.typography.titleMedium)
                if (selected.characters.isEmpty()) {
                    Text("No characters added yet. Recognition is not connected; catalogue entries can be added manually for now.")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(selected.characters.sortedBy { it.id }, key = { it.id }) { character ->
                            val portrait = character.portraitFile?.let {
                                File(File(store.profileDirectory(selected.id), "extracted_portraits"), it)
                            }
                            CharacterCollectionRow(character, portrait)
                        }
                    }
                }
                if (savedCrops.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(savedCrops, key = { it.name }) { crop ->
                            CropThumbnail(crop)
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        runCatching { store.exportJson(selected) }
                            .onSuccess { json ->
                                pendingExportJson = json
                                val filename = "${selected.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}_box.json"
                                runCatching { jsonExporter.launch(filename) }
                                    .onFailure {
                                        pendingExportJson = null
                                        exportMessage = "Could not open Android's save-file screen: ${it.localizedMessage ?: it.javaClass.simpleName}"
                                    }
                            }
                            .onFailure {
                                exportMessage = "Could not prepare JSON: ${it.localizedMessage ?: it.javaClass.simpleName}"
                            }
                    },
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

    if (showAddCharacter && selected != null) {
        AddCharacterDialog(
            referenceStore = referenceStore,
            referenceVersion = referenceCount,
            portraitFiles = store.extractedPortraits(selected.id),
            onDismiss = { showAddCharacter = false },
            onAdd = { character, portraitName ->
                showAddCharacter = false
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { store.addCharacter(selected.id, character, portraitName) }
                    }
                    result.onSuccess {
                        profiles = store.load()
                        cropMessage = "Added ${character.name} (#${character.id}) to ${selected.name}."
                    }.onFailure {
                        cropMessage = "Could not add character: ${it.localizedMessage}"
                    }
                }
            }
        )
    }

    if (showScreenshotPicker && selected != null) {
        AlertDialog(
            onDismissRequest = { showScreenshotPicker = false },
            title = { Text("Choose a source screenshot") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(selected.screenshots) { name ->
                        TextButton(
                            onClick = {
                                val source = File(store.profileDirectory(selected.id), name)
                                showScreenshotPicker = false
                                if (source.isFile && source.length() > 0L) {
                                    cropMessage = null
                                    cropScreenshot = source
                                } else {
                                    cropMessage = "That imported screenshot is missing or empty. Import it again and retry."
                                }
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

    if (showAutoExtractPicker && selected != null) {
        AlertDialog(
            onDismissRequest = { showAutoExtractPicker = false },
            title = { Text("Choose screenshot to calibrate") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The app will ask you to mark the whole five-column grid, then one complete character tile.")
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(selected.screenshots) { name ->
                            TextButton(
                                onClick = {
                                    val source = File(store.profileDirectory(selected.id), name)
                                    showAutoExtractPicker = false
                                    if (source.isFile && source.length() > 0L) {
                                        calibratedGridBounds = null
                                        gridCalibrationStage = 1
                                        gridCalibrationScreenshot = source
                                        cropMessage = "Step 1 of 2: select the complete character grid, from the first card edge to the last card edge."
                                    } else {
                                        cropMessage = "That imported screenshot is missing or empty. Import it again and retry."
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(name) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAutoExtractPicker = false }) { Text("Close") }
            }
        )
    }

    gridCalibrationScreenshot?.let { screenshot ->
        val stage = gridCalibrationStage
        key(stage) {
            CropReviewDialog(
                screenshot = screenshot,
                title = if (stage == 1) "Step 1: Select the whole grid" else "Step 2: Select one complete card",
                instructions = if (stage == 1) {
                    "Drag around the full five-column character grid only. Exclude the game header, filter controls, and bottom navigation."
                } else {
                    "Drag around exactly one complete character tile, including its outer border and bottom level area. Do not select neighboring cards."
                },
                onDismiss = {
                    gridCalibrationScreenshot = null
                    gridCalibrationStage = 0
                    calibratedGridBounds = null
                },
                onSave = { bounds ->
                    if (stage == 1) {
                        calibratedGridBounds = bounds
                        gridCalibrationStage = 2
                        cropMessage = "Step 2 of 2: select one complete card tile inside the grid."
                    } else {
                        val profileId = selectedId
                        val gridBounds = calibratedGridBounds
                        gridCalibrationScreenshot = null
                        gridCalibrationStage = 0
                        calibratedGridBounds = null
                        if (profileId == null || gridBounds == null) {
                            cropMessage = "Grid calibration was lost. Please start Auto Extract again."
                        } else {
                            cropMessage = "Calibrating five columns and extracting card tiles…"
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        GridCalibrationExtractor.extract(
                                            context, profileId, screenshot, gridBounds, bounds
                                        )
                                    }
                                }
                                result.onSuccess { files ->
                                    cropMessage = "Extracted ${files.size} card tiles using your calibrated five-column grid. Review the thumbnails; no character IDs or quantities were assigned."
                                }.onFailure { error ->
                                    cropMessage = "Grid extraction failed: ${error.localizedMessage ?: error.javaClass.simpleName}"
                                }
                            }
                        }
                    }
                }
            )
        }
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
private fun AddCharacterDialog(
    referenceStore: CharacterReferenceStore,
    referenceVersion: Int,
    portraitFiles: List<File>,
    onDismiss: () -> Unit,
    onAdd: (CharacterReference, String?) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var cropExpanded by remember { mutableStateOf(false) }
    var selectedCrop by remember { mutableStateOf<String?>(null) }
    val matches by produceState<List<CharacterReference>>(
        initialValue = emptyList(),
        key1 = query,
        key2 = referenceVersion
    ) {
        value = withContext(Dispatchers.IO) { referenceStore.search(query, 80) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add character to collection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search name or character ID") },
                    singleLine = true
                )
                OutlinedButton(
                    onClick = { cropExpanded = true },
                    enabled = portraitFiles.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(selectedCrop?.let { "Portrait crop: $it" } ?: "Portrait crop: none")
                }
                DropdownMenu(expanded = cropExpanded, onDismissRequest = { cropExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("No portrait crop") },
                        onClick = { selectedCrop = null; cropExpanded = false }
                    )
                    portraitFiles.forEach { file ->
                        DropdownMenuItem(
                            text = { Text(file.name) },
                            onClick = { selectedCrop = file.name; cropExpanded = false }
                        )
                    }
                }
                Text("Choose a character ID. Adding the same ID again increases its physical-copy quantity.")
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(matches, key = { it.id }) { character ->
                        TextButton(
                            onClick = { onAdd(character, selectedCrop) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(character.name)
                                Text("#${character.id} • ${character.type} • ${character.stars}")
                            }
                        }
                    }
                    if (query.isNotBlank() && matches.isEmpty()) {
                        item { Text("No matching character IDs found.") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun CharacterCollectionRow(character: BoxCharacter, portraitFile: File?) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = portraitFile?.absolutePath) {
        value = if (portraitFile?.isFile == true) {
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(portraitFile.absolutePath) }
        } else null
    }
    DisposableEffect(bitmap) {
        onDispose { bitmap?.takeIf { !it.isRecycled }?.recycle() }
    }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(76.dp)) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = "Character ${character.id} portrait",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) { Text("No image", style = MaterialTheme.typography.labelSmall) }
                }
                if (character.quantity > 1) {
                    Text(
                        "×${character.quantity}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .background(Color.Black.copy(alpha = 0.85f), CircleShape)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(character.name, style = MaterialTheme.typography.titleSmall)
                Text("#${character.id} • ${character.type} • ${character.stars}")
                if (character.classes.isNotEmpty()) {
                    Text(character.classes.joinToString(" / "), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
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

private fun decodeCropPreview(file: File): Bitmap? {
    if (!file.isFile || file.length() <= 0L) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        val largestDimension = max(bounds.outWidth, bounds.outHeight)
        while (largestDimension / sampleSize > 1400) sampleSize *= 2
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun CropReviewDialog(
    screenshot: File,
    title: String = "Select portrait area",
    instructions: String = "Drag over the character portrait. The original screenshot will remain unchanged.",
    onDismiss: () -> Unit,
    onSave: (RectF) -> Unit
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = screenshot.absolutePath) {
        value = withContext(Dispatchers.IO) { decodeCropPreview(screenshot) }
    }
    // Keep the preview bitmap alive for the lifetime of the dialog. Recycling it from
    // DisposableEffect can race Compose's Image renderer during dialog transitions.
    val imageBitmap = remember(bitmap) { bitmap?.asImageBitmap() }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }

    val sourceBitmap = bitmap
    fun fittedImageRect(size: IntSize): Rect {
        if (size.width <= 0 || size.height <= 0) return Rect.Zero
        val source = sourceBitmap ?: return Rect.Zero
        val scale = min(
            size.width.toFloat() / source.width.toFloat(),
            size.height.toFloat() / source.height.toFloat()
        )
        val width = source.width * scale
        val height = source.height * scale
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
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(instructions)
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
