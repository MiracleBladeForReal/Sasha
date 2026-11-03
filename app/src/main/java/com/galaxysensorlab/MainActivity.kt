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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
@OptIn(ExperimentalLayoutApi::class)
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
    var showProfiles by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    val camera = model.calibration.profile(settings.profileId)
    val ready = model.videoUri != null && !model.busy
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("GALAXY / EDGE CAPTURE", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("Una nuova ripresa, la tua grana.", style = MaterialTheme.typography.titleMedium)
                }
                Text("v0.4", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = settings.original, onClick = { model.update(settings.copy(original = !settings.original)) },
                            label = { Text("Originale") }, enabled = ready)
                        FilterChip(selected = settings.compare, onClick = { model.update(settings.copy(compare = !settings.compare, original = false)) },
                            label = { Text("Prima / dopo") }, enabled = ready)
                        TextButton(onClick = { model.update(LabSettings(noise = 0f, profileId = camera.id, iso = settings.iso, noiseSeed = settings.noiseSeed)) }, enabled = ready) { Text("Azzera") }
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
                            Text(camera.displayName, style = MaterialTheme.typography.titleMedium)
                            Text("${camera.dataset} · ISO ${camera.minIso.toInt()}–${camera.maxIso.toInt()}. Applicazione approssimata a video già elaborati.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Telefono: ${Build.MANUFACTURER} ${Build.MODEL}", style = MaterialTheme.typography.labelSmall)
                            OutlinedButton(onClick = { showProfiles = true }, enabled = !model.busy,
                                modifier = Modifier.fillMaxWidth()) { Text("Scegli fotocamera") }
                            TextButton(onClick = { showSource = true }, contentPadding = PaddingValues(0.dp)) { Text("Dati, fonte e licenze") }
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
                                    "Notte" -> LabSettings(iso = 3200f, noise = 0.9f, exposure = -0.4f, warmth = -0.2f, grainSize = 1.5f, grainChroma = 0.35f)
                                    else -> LabSettings(iso = 800f, noise = 0.45f, warmth = 0.18f, saturation = 0.8f, contrast = 1.15f, vignette = 0.3f, grainSize = 4f, grainChroma = 0.08f)
                                }.copy(profileId = camera.id, noiseSeed = settings.noiseSeed))
                            }, enabled = ready, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) { Text(name) }
                        }
                    }
                }
                item {
                    SectionLabel("01 / SENSORE")
                    val logRange = ln(camera.maxIso / camera.minIso) / ln(2f)
                    val isS6 = camera.id == NoiseCalibration.DEFAULT_PROFILE
                    LabSlider("ISO simulato", ln(settings.iso.coerceIn(camera.minIso, camera.maxIso) / camera.minIso) / ln(2f),
                        0f..logRange, settings.iso.roundToInt().toString(), ready, steps = if (isS6) 4 else 0) {
                        val logIso = if (isS6) it.roundToInt().toFloat() else it
                        model.update(settings.copy(iso = (camera.minIso * 2f.pow(logIso)).roundToInt().toFloat()))
                    }
                    LabSlider("Intensità rumore", settings.noise, 0f..2f, percent(settings.noise), ready) {
                        model.update(settings.copy(noise = it))
                    }
                    Text("Scala base ${camera.dataset} sul grigio 18%: σ = ${format(model.calibration.sigma(settings.iso, 0.18f, camera.id) * settings.noise * 100f)}%",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Rumore di ripresa", style = MaterialTheme.typography.titleMedium)
                                    Text(if (settings.naturalGrain) "Attivo · grana correlata" else "Spento · rumore classico",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = settings.naturalGrain, onCheckedChange = {
                                    model.update(settings.copy(naturalGrain = it))
                                }, enabled = ready)
                            }
                            Text("Il profilo selezionato e i suoi coefficienti ISO restano gli stessi in entrambe le modalità.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (settings.naturalGrain) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Simulazione di ripresa", Modifier.weight(1f))
                                    Switch(checked = settings.captureMode, onCheckedChange = {
                                        model.update(settings.copy(captureMode = it))
                                    }, enabled = ready)
                                }
                                Text(if (settings.captureMode) "Rumore fotonico dipendente dalla luce + componente di lettura. Ogni nuova ripresa ha una sequenza casuale diversa."
                                    else "Grana naturale della versione precedente.", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = { model.newTake() }, enabled = ready,
                                    modifier = Modifier.fillMaxWidth()) { Text("Nuova ripresa · ${settings.noiseSeed}") }
                                if (settings.captureMode) {
                                    LabSlider("Rumore di lettura aggiunto", settings.readNoise, 0f..0.02f,
                                        "${format(settings.readNoise * 100f)}%", ready) { model.update(settings.copy(readNoise = it)) }
                                    LabSlider("Rumore nelle ombre", settings.shadowGain, 0f..3f,
                                        percent(settings.shadowGain), ready) { model.update(settings.copy(shadowGain = it)) }
                                    LabSlider("Protezione alte luci", settings.highlightProtect, 0f..1f,
                                        percent(settings.highlightProtect), ready) { model.update(settings.copy(highlightProtect = it)) }
                                    LabSlider("Coerenza nel tempo", settings.temporalCoherence, 0f..1f,
                                        percent(settings.temporalCoherence), ready) { model.update(settings.copy(temporalCoherence = it)) }
                                    Text("Questi quattro controlli sono regolazioni creative, non misure del telefono. La coerenza lega il rumore di due istanti vicini senza fondere il video. L’esportazione usa la stessa sequenza dell’anteprima.",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                LabSlider("Dimensione grana", settings.grainSize, 0.5f..12f,
                                    "${format(settings.grainSize)} px*", ready) { model.update(settings.copy(grainSize = it)) }
                                LabSlider("Rumore luminosità", settings.grainLuma, 0f..1.5f,
                                    percent(settings.grainLuma), ready) { model.update(settings.copy(grainLuma = it)) }
                                LabSlider("Rumore colore", settings.grainChroma, 0f..1.5f,
                                    percent(settings.grainChroma), ready) { model.update(settings.copy(grainChroma = it)) }
                                LabSlider("Morbidezza grana", settings.grainSoftness, 0f..1f,
                                    percent(settings.grainSoftness), ready) { model.update(settings.copy(grainSoftness = it)) }
                                LabSlider("Rinnovo del disegno", settings.grainRate, 1f..60f,
                                    "${settings.grainRate.roundToInt()} / s", ready) { model.update(settings.copy(grainRate = it.roundToInt().toFloat())) }
                                Text("*Dimensione riferita a 1080p, adattata alla sorgente. Il rinnovo cambia solo la grana: non fonde i fotogrammi del video.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
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
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionLabel("04 / ESPORTA MP4")
                            Text("H.264 · audio AAC · qualità alta per preservare la grana", style = MaterialTheme.typography.bodySmall)
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
                                Button(onClick = { exporter.launch("GalaxySensorLab-Edge-Capture-${System.currentTimeMillis()}.mp4") },
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
                    Text("Simulazione di rumore da fotocamera · elaborazione locale\nIl risultato può ricordare una ripresa reale; il video conserva la propria origine e gli eventuali difetti di partenza.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (showProfiles) CameraPicker(model.calibration, camera.id,
        onSelect = { selected -> model.update(settings.copy(profileId = selected.id)); showProfiles = false },
        onDismiss = { showProfiles = false })
    if (showSource) AlertDialog(onDismissRequest = { showSource = false }, title = { Text("Dati del profilo") },
        text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val point = model.calibration.at(settings.iso, camera.id)
            Text("${camera.displayName} · ${camera.dataset} · ${camera.license}")
            Text(camera.attribution)
            Text("ISO pubblicati: ${camera.points.joinToString { it.iso.toInt().toString() }}")
            Text("Varianza RAW = a × segnale + b. Verde: a = ${point.shot[1]}, b = ${point.read[1]}.")
            Text(if (camera.dataset == "SIDD") "I coefficienti originali S6 Edge sono conservati, compresi i valori nulli di b."
                 else "Coefficienti RGB darktable originali. ISO intermedi interpolati linearmente; niente estrapolazione. In presenza di più misure allo stesso ISO usiamo la prima pubblicata. I valori b negativi sono conservati nei dati: nella simulazione fotonica le componenti fisiche sono limitate separatamente a zero; nella grana precedente si limita solo la varianza totale.")
            Text("La modalità naturale modifica dimensione e correlazione della grana con regolazioni creative. Applicare un profilo RAW a un MP4 già elaborato è un’approssimazione, non una replica della fotocamera.")
            Text("Riferimenti S6 Edge: GSMArena pubblica campioni MP4 originali; DPReview mostra riprese diurne e notturne ricompresse da YouTube. Sono riferimenti visivi pubblicati, non una calibrazione video misurata per questa app.")
            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://www.gsmarena.com/samsung_galaxy_s6_edge-review-1236p9.php"))) } }) { Text("Video originali GSMArena") }
            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://www.dpreview.com/reviews/2097209656/samsung-galaxy-s6-edge-camera-review/"))) } }) { Text("Riprese diurne / notturne DPReview") }
            TextButton(onClick = { showSource = false; showLicenses = true }) { Text("Licenze e codice sorgente") }
        } },
        confirmButton = { TextButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(camera.source))) }
        }) { Text("Apri fonte") } },
        dismissButton = { TextButton(onClick = { showSource = false }) { Text("Chiudi") } })
    if (showLicenses) {
        val licenseText = remember { context.assets.open("THIRD-PARTY-NOTICES.txt").bufferedReader().use { it.readText() } +
            "\n\n" + context.assets.open("LICENSE-GPL-3.0.txt").bufferedReader().use { it.readText() } }
        AlertDialog(onDismissRequest = { showLicenses = false }, title = { Text("Licenze · Edge Capture") },
            text = { Text(licenseText, modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                    "https://github.com/MiracleBladeForReal/Sasha/tree/${BuildConfig.SOURCE_COMMIT}"))) }
            }) { Text("Codice sorgente") } },
            dismissButton = { TextButton(onClick = { showLicenses = false }) { Text("Chiudi") } })
    }
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
