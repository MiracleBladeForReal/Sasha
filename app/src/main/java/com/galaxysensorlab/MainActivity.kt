package com.galaxysensorlab

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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

@UnstableApi
class MainActivity : ComponentActivity() {
    private val model: LabViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFB4F272),onPrimary=Color(0xFF172707),
                background=Color(0xFF0C1015),surface=Color(0xFF151B23),secondary=Color(0xFFB8A3FF))) { LabScreen(model) }
        }
    }
    override fun onStop() { model.player.pause(); super.onStop() }
}

@UnstableApi
@Composable
private fun LabScreen(model: LabViewModel) {
    val context=LocalContext.current
    val s=model.settings
    val camera=model.calibration.profile(s.profileId)
    val ready=model.videoUri!=null && !model.busy
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> model.loadVideo(uri) } }
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { it?.let(model::export) }
    var showProfiles by remember { mutableStateOf(false) }
    var showData by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showIso by remember { mutableStateOf(false) }
    fun open(url: String) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) } }
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("GALAXY / EDGE SENSOR",color=MaterialTheme.colorScheme.primary,
                        style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold)
                    Text("Modello di rumore del sensore",style=MaterialTheme.typography.titleMedium)
                }
                Text("v0.5",style=MaterialTheme.typography.labelSmall)
            }
            Box(Modifier.fillMaxWidth().aspectRatio(16f/9f).background(Color.Black),contentAlignment=Alignment.Center) {
                if(model.videoUri!=null) {
                    AndroidView(factory={ ctx -> PlayerView(ctx).apply {
                        player=model.player; keepScreenOn=true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    } },update={ it.player=model.player },modifier=Modifier.fillMaxSize(),onRelease={ it.player=null })
                    if(s.compare && !s.original) Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(8.dp),
                        horizontalArrangement=Arrangement.SpaceBetween) {
                        Text("ORIGINALE",Modifier.background(Color.Black.copy(alpha=0.7f)).padding(4.dp),style=MaterialTheme.typography.labelSmall)
                        Text("SENSORE",Modifier.background(Color.Black.copy(alpha=0.7f)).padding(4.dp),style=MaterialTheme.typography.labelSmall)
                    }
                } else Button(onClick={ picker.launch(arrayOf("video/*")) }) { Text("Importa video SDR") }
            }
            LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(model.videoName,maxLines=1)
                            Text(model.videoInfo.ifEmpty { "Elaborazione sul telefono" },style=MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick={ picker.launch(arrayOf("video/*")) },enabled=!model.busy) { Text("Cambia") }
                    }
                }
                item {
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected=s.original,onClick={ model.update(s.copy(original=!s.original)) },enabled=ready,label={ Text("Originale") })
                        FilterChip(selected=s.compare,onClick={ model.update(s.copy(compare=!s.compare,original=false)) },enabled=ready,label={ Text("Prima / dopo") })
                    }
                }
                item {
                    Card {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("PROFILO SENSORE",color=MaterialTheme.colorScheme.secondary,style=MaterialTheme.typography.labelSmall)
                            Text(camera.displayName,style=MaterialTheme.typography.titleMedium)
                            Text("${camera.dataset} · ISO pubblicati ${camera.minIso.toInt()}–${camera.maxIso.toInt()}",style=MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick={ showProfiles=true },enabled=!model.busy,modifier=Modifier.fillMaxWidth()) { Text("Scegli fotocamera") }
                            Box {
                                OutlinedButton(onClick={ showIso=true },enabled=!model.busy) { Text("ISO ${s.iso.toInt()}") }
                                DropdownMenu(expanded=showIso,onDismissRequest={ showIso=false },modifier=Modifier.heightIn(max=280.dp)) {
                                    camera.points.forEach { point -> DropdownMenuItem(text={ Text("ISO ${point.iso.toInt()}") },onClick={
                                        model.update(s.copy(iso=point.iso)); showIso=false
                                    }) }
                                }
                            }
                            Text("Varianza per canale = a × segnale + b. Si applicano i coefficienti pubblicati, senza moltiplicatori artistici.",style=MaterialTheme.typography.bodySmall)
                            TextButton(onClick={ showData=true }) { Text("Dati e riferimenti originali") }
                        }
                    }
                }
                item {
                    Card {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text("ESPORTAZIONE MP4",color=MaterialTheme.colorScheme.secondary,style=MaterialTheme.typography.labelSmall)
                            Text("H.264 / AAC · risoluzione e frequenza della sorgente",style=MaterialTheme.typography.bodySmall)
                            Text("Tieni l’app aperta durante il salvataggio.",style=MaterialTheme.typography.bodySmall)
                            if(model.busy) {
                                val progress=model.progress
                                if(progress==null || model.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                                else LinearProgressIndicator(progress={ progress/100f },modifier=Modifier.fillMaxWidth())
                                Text(if(model.saving) "Salvataggio…" else "Elaborazione ${progress?.let { "$it%" } ?: "in corso…"}")
                                TextButton(onClick=model::cancelExport,enabled=!model.saving) { Text("Annulla") }
                            } else Button(onClick={ exporter.launch("GalaxySensorLab-Sensor-${System.currentTimeMillis()}.mp4") },
                                enabled=ready,modifier=Modifier.fillMaxWidth()) { Text("Salva video elaborato") }
                            model.resultUri?.let { uri -> OutlinedButton(onClick={
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"video/mp4")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                            },modifier=Modifier.fillMaxWidth()) { Text("Apri il risultato") } }
                        }
                    }
                }
                model.message?.let { message -> item { Card { Column(Modifier.padding(16.dp)) {
                    Text(message)
                    TextButton(onClick=model::clearMessage) { Text("Chiudi") }
                } } } }
                item {
                    Text("I profili RAW descrivono il rumore del sensore. La loro applicazione al video è un’approssimazione: non comprende l’ISP proprietario della fotocamera.",
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick={ showLicenses=true }) { Text("Licenze e codice sorgente") }
                }
            }
        }
    }
    if(showProfiles) CameraPicker(model.calibration,camera.id,onSelect={ model.update(s.copy(profileId=it.id)); showProfiles=false },onDismiss={ showProfiles=false })
    if(showData) AlertDialog(onDismissRequest={ showData=false },title={ Text("Dati del sensore") },text={
        Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            val point=model.calibration.at(s.iso,camera.id)
            Text("${camera.displayName} · ${camera.dataset} · ${camera.license}")
            Text(camera.attribution)
            Text("ISO ${s.iso.toInt()} — coefficienti originali RGB")
            Text("a: ${point.shot.joinToString()}\nb: ${point.read.joinToString()}")
            Text("Non vengono aggiunti rumore di lettura, curve di luce o correlazioni non misurati. I valori b negativi restano nel calcolo della varianza totale, limitata a zero.")
            Text("Campioni S6 Edge pubblicati come file originali da GSMArena. Video compresso del telefono, non dati RAW; ISO e trattamento ISP non sono documentati nel campione.")
            TextButton(onClick={ open("https://fdn.gsmarena.com/vv/reviewsimg/samsung-galaxy-s6-edge/camera/gsmarena_v002.mp4") }) { Text("Originale S6 Edge · 1080p30") }
            TextButton(onClick={ open("https://fdn.gsmarena.com/vv/reviewsimg/samsung-galaxy-s6-edge/camera/gsmarena_v001.mp4") }) { Text("Originale S6 Edge · 2160p30") }
            TextButton(onClick={ open("https://www.gsmarena.com/samsung_galaxy_s6_edge-review-1236p9.php") }) { Text("Fonte dei filmati") }
        }
    },confirmButton={ TextButton(onClick={ open(camera.source) }) { Text("Fonte coefficienti") } },dismissButton={ TextButton(onClick={ showData=false }) { Text("Chiudi") } })
    if(showLicenses) {
        val license=remember { context.assets.open("THIRD-PARTY-NOTICES.txt").bufferedReader().use { it.readText() }+
            "\n\n"+context.assets.open("LICENSE-GPL-3.0.txt").bufferedReader().use { it.readText() } }
        AlertDialog(onDismissRequest={ showLicenses=false },title={ Text("Licenze · Edge Sensor") },
            text={ Text(license,Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),style=MaterialTheme.typography.bodySmall) },
            confirmButton={ TextButton(onClick={ open("https://github.com/MiracleBladeForReal/Sasha/tree/${BuildConfig.SOURCE_COMMIT}") }) { Text("Codice sorgente") } },
            dismissButton={ TextButton(onClick={ showLicenses=false }) { Text("Chiudi") } })
    }
}
