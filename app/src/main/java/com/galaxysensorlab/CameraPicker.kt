package com.galaxysensorlab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun CameraPicker(
    calibration: NoiseCalibration,
    selectedId: String,
    onSelect: (CameraNoiseProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val matches = remember(query, calibration) { calibration.search(query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scegli fotocamera") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it },
                    label = { Text("Cerca marca o modello") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("camera-search"))
                Text("${matches.size} profili · SIDD e darktable", style = MaterialTheme.typography.labelMedium)
                LazyColumn(Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 340.dp)) {
                    items(matches, key = { it.id }) { camera ->
                        ListItem(
                            headlineContent = { Text(camera.displayName) },
                            supportingContent = { Text("${camera.dataset} · ISO ${camera.minIso.toInt()}–${camera.maxIso.toInt()}") },
                            trailingContent = { RadioButton(selected = camera.id == selectedId, onClick = null) },
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(camera) }
                        )
                    }
                    if (matches.isEmpty()) item { Text("Nessun profilo trovato. Prova una sola parola, per esempio Nikon o Nord.") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } }
    )
}
