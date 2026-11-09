package com.galaxysensorlab

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import java.util.Locale

@UnstableApi
class MainActivity : ComponentActivity() {
    private val model: LabViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LabTheme { LabScreen(model) } }
    }
    override fun onStop() {
        model.player.pause()
        super.onStop()
    }
}

@Composable
private fun LabTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFB4F272), onPrimary = Color(0xFF172707),
        secondary = Color(0xFFB8A3FF), background = Color(0xFF0C1015),
        surface = Color(0xFF151B23), surfaceVariant = Color(0xFF252E39),
        onSurface = Color(0xFFF0F3F7), onSurfaceVariant = Color(0xFFB4BFCC)
    ), content = content)
}

@UnstableApi
@Composable
private fun LabScreen(model: LabViewModel) {
    val context = LocalContext.current
    val settings = model.settings
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.loadVideo(it) }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        uri?.let { model.export(it) }
    }
    var showSource by remember { mutableStateOf(false) }
    val ready = model.videoUri != null && !model.busy
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("GALAXY / SENSOR LAB", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("Il tuo video. Un altro carattere.", style = MaterialTheme.typography.titleMedium)
                }
                Text("v0.3", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
                if (model.videoUri != null) {
                    AndroidView(
                        factory = { ctx -> PlayerView(ctx).apply {
                            player = model.player
                            keepScreenOn = true
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        } },
                        update = { it.player = model.player },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { it.player = null }
                    )
                    if (settings.compare && !settings.original) {
                        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            PreviewLabel("ORIGINALE")
                            PreviewLabel("ELABORATO")
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Da video a laboratorio", style = MaterialTheme.typography.titleLarge)
                        Text("Importa una clip e prova il profilo sensore", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { picker.launch(arrayOf("video/*")) }) { Text("Importa video") }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(model.videoName, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                            Text(model.videoInfo.ifEmpty { "Tutto sul telefono · nessun caricamento online" },
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { picker.launch(arrayOf("video/*")) }, enabled = !model.busy) { Text("Cambia") }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = settings.original, onClick = { model.update(settings.copy(original = !settings.original)) },
                            label = { Text("Originale") }, enabled = ready)
                        FilterChip(selected = settings.compare, onClick = { model.update(settings.copy(compare = !settings.compare, original = false)) },
                            label = { Text("Prima / dopo") }, enabled = ready)
                        TextButton(onClick = { model.update(LabSettings(noise = 0f)) }, enabled = ready) { Text("Azzera") }
                    }
                    if (settings.compare && !settings.original) {
                        LabSlider("Linea di confronto", settings.split, 0.05f..0.95f, "${(settings.split * 100).roundToInt()}%", ready) {
                            model.update(settings.copy(split = it))
                        }
                    }
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("PROFILO DI RUMORE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            Text("Samsung Galaxy S6 Edge", style = MaterialTheme.typography.titleMedium)
                            Text("Coefficienti DNG pubblicati nel dataset SIDD. Applicazione approssimata a video già elaborati.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Telefono: ${Build.MANUFACTURER} ${Build.MODEL}", style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { showSource = true }, contentPadding = PaddingValues(0.dp)) { Text("Dati e fonte GitHub") }
                        }
                    }
                }
                item {
                    Text("Parti da un look", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Pulito", "Notte", "Cinema").forEach { name ->
                            OutlinedButton(onClick = {
                                model.update(when (name) {
                                    "Pulito" -> LabSettings(iso = 100f, noise = 0f, denoise = 0.35f)
                                    "Notte" -> LabSettings(iso = 3200f, noise = 0.9f, exposure = -0.4f, warmth = -0.2f)
                                    else -> LabSettings(iso = 800f, noise = 0.45f, warmth = 0.18f, saturation = 0.8f, contrast = 1.15f, vignette = 0.3f)
                                })
                            }, enabled = ready, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) { Text(name) }
                        }
                    }
                }
                item {
                    SectionLabel("01 / SENSORE")
                    LabSlider("ISO simulato", (ln(settings.iso / 100f) / ln(2f)), 0f..5f,
                        settings.iso.roundToInt().toString(), ready, steps = 4) {
                        model.update(settings.copy(iso = 100f * 2f.pow(it.roundToInt())))
                    }
                    LabSlider("Intensità rumore", settings.noise, 0f..2f, percent(settings.noise), ready) {
                        model.update(settings.copy(noise = it))
                    }
                    Text("Rumore teorico sul grigio 18%: σ = ${format(model.calibration.sigma(settings.iso, 0.18f) * settings.noise * 100f)}%",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    SectionLabel("02 / SVILUPPO")
                    LabSlider("Esposizione", settings.exposure, -3f..3f, "${format(settings.exposure)} EV", ready) { model.update(settings.copy(exposure = it)) }
                    LabSlider("Temperatura", settings.warmth, -1f..1f, if (settings.warmth < 0) "Fredda" else if (settings.warmth > 0) "Calda" else "Neutra", ready) { model.update(settings.copy(warmth = it)) }
                    LabSlider("Saturazione", settings.saturation, 0f..2f, percent(settings.saturation), ready) { model.update(settings.copy(saturation = it)) }
                    LabSlider("Contrasto", settings.contrast, 0.5f..1.5f, percent(settings.contrast), ready) { model.update(settings.copy(contrast = it)) }
                }
                item {
                    SectionLabel("03 / FINITURA")
                    LabSlider("Riduzione rumore spaziale", settings.denoise, 0f..1f, percent(settings.denoise), ready) { model.update(settings.copy(denoise = it)) }
                    LabSlider("Nitidezza", settings.sharpness, 0f..1f, percent(settings.sharpness), ready) { model.update(settings.copy(sharpness = it)) }
                    LabSlider("Vignettatura", settings.vignette, 0f..1f, percent(settings.vignette), ready) { model.update(settings.copy(vignette = it)) }
                }
                item {
                    SectionLabel("04 / COMPRESSIONE JPEG")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Compressione JPEG", modifier = Modifier.weight(1f))
                        Switch(checked = settings.jpegEnabled, enabled = ready,
                            onCheckedChange = { model.update(settings.copy(jpegEnabled = it)) },
                            modifier = Modifier.semantics { contentDescription = "Attiva compressione JPEG" })
                    }
                    LabSlider("Intensità compressione", settings.jpegAmount, 0f..1f,
                        percent(settings.jpegAmount), ready && settings.jpegEnabled) {
                        model.update(settings.copy(jpegAmount = it))
                    }
                    Text("Blocchi 8×8, dettagli impastati e aloni. A 0% nessuna compressione aggiunta. Visibile in anteprima e nel video salvato.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionLabel("05 / ESPORTA MP4")
                            Text("H.264 · audio AAC · effetti applicati all’intero fotogramma", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(720, 1080).forEach { height ->
                                    FilterChip(selected = model.exportHeight == height, onClick = { model.exportHeight = height },
                                        enabled = !model.busy, label = { Text("${height}p") })
                                }
                            }
                            Text("Il video non viene ingrandito oltre la risoluzione originale. Tieni l’app aperta durante l’esportazione.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (model.busy) {
                                val progress = model.progress
                                if (progress == null || model.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                                else LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                                Text(if (model.saving) "Salvataggio…" else "Elaborazione ${progress?.let { "$it%" } ?: "in corso…"}")
                                TextButton(onClick = { model.cancelExport() }, enabled = !model.saving) { Text("Annulla esportazione") }
                            } else {
                                Button(onClick = { exporter.launch("GalaxySensorLab-${System.currentTimeMillis()}.mp4") },
                                    enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Salva video elaborato") }
                            }
                            model.resultUri?.let { uri ->
                                OutlinedButton(onClick = {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                                        .onFailure { Toast.makeText(context, "Apri il video da Archivio", Toast.LENGTH_LONG).show() }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Apri il risultato") }
                                TextButton(onClick = {
                                    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                        type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }, "Condividi video")) }
                                }) { Text("Condividi") }
                            }
                        }
                    }
                }
                model.message?.let { message -> item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(message, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { model.clearMessage() }) { Text("Chiudi") }
                        }
                    }
                } }
                item {
                    Text("Laboratorio sperimentale · elaborazione locale\nIl profilo S6 non calibra automaticamente il tuo modello e non ricostruisce il RAW o l’ISP proprietario Samsung.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (showSource) AlertDialog(onDismissRequest = { showSource = false }, title = { Text("Dati reali, limiti chiari") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val point = model.calibration.at(settings.iso)
            Text("SIDD · Abdelhamed, Lin e Brown · CVPR 2018. Licenza MIT. Galaxy S6 Edge, ISO 100, 200, 400, 800, 1600 e 3200.")
            Text("Varianza RAW = β₁ × segnale + β₂. Verde: β₁ = ${point.shot[1]}, β₂ = ${point.read[1]}. I valori nulli di β₂ sono quelli pubblicati.")
            Text("Un MP4 contiene già rumore, compressione e sviluppo. Questa è una simulazione aggiuntiva in RGB lineare, non una misura del video originale né un’emulazione completa del sensore.")
        } },
        confirmButton = { TextButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(model.calibration.source))) }
        }) { Text("Apri GitHub") } },
        dismissButton = { TextButton(onClick = { showSource = false }) { Text("Chiudi") } })
}

@Composable
private fun SectionLabel(text: String) { Text(text, style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold) }

@Composable
private fun PreviewLabel(text: String) {
    Text(text, modifier = Modifier.background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp)).padding(6.dp),
        style = MaterialTheme.typography.labelSmall, color = Color.White)
}

@Composable
private fun LabSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String,
                      enabled: Boolean, steps: Int = 0, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(display, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps, enabled = enabled)
    }
}

private fun format(value: Float) = String.format(Locale.ITALIAN, "%.2f", value)
private fun percent(value: Float) = "${(value * 100).roundToInt()}%"

