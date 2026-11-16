package com.galaxysensorlab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
private fun Control(label: String,value: Float,range: ClosedFloatingPointRange<Float>,enabled: Boolean,onChange: (Float)->Unit) {
    Text("$label: ${String.format(java.util.Locale.ROOT,"%.3f",value)}",style=MaterialTheme.typography.bodySmall)
    Slider(value=value,onValueChange=onChange,valueRange=range,enabled=enabled)
}

internal fun LazyListScope.completeControls(s: LabSettings,enabled: Boolean,update: (LabSettings)->Unit,newTake: ()->Unit) {
    item {
        Card { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("PRESET E RIPRISTINO")
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                listOf("Pulito","Notte","Cinema").forEach { name ->
                    OutlinedButton(onClick={ update(when(name) {
                        "Pulito" -> LabSettings(iso=100f,noise=0f,denoise=0.35f)
                        "Notte" -> LabSettings(iso=3200f,noiseMode=3,noise=0.9f,exposure=-0.4f,warmth=-0.2f,grainSize=1.5f,grainChroma=0.35f)
                        else -> LabSettings(iso=800f,noiseMode=2,noise=0.45f,warmth=0.18f,saturation=0.8f,contrast=1.15f,vignette=0.3f,grainSize=4f,grainChroma=0.08f)
                    }.copy(profileId=s.profileId,noiseSeed=s.noiseSeed,defectSeed=s.defectSeed)) },enabled=enabled,
                        modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=2.dp)) { Text(name) }
                }
            }
            TextButton(onClick={ update(LabSettings(profileId=s.profileId,iso=s.iso,noise=0f,noiseSeed=s.noiseSeed,defectSeed=s.defectSeed)) },enabled=enabled) { Text("Azzera tutti gli effetti") }
            if(s.compare && !s.original) Control("Linea di confronto",s.split,0.05f..0.95f,enabled) { update(s.copy(split=it)) }
        } }
    }
    item {
        Card { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("RUMORE E GRANA")
            listOf("Sensore (0.7)","Classico (JPEG 0.3)","Grana naturale (Grain 0.3)","Simulazione di ripresa (Edge Capture)").forEachIndexed { i,label ->
                FilterChip(selected=s.noiseMode==i,onClick={ update(s.copy(noiseMode=i)) },enabled=enabled,label={ Text(label) })
            }
            Control("Quantità rumore",s.noise,0f..2f,enabled) { update(s.copy(noise=it)) }
            OutlinedButton(onClick=newTake,enabled=enabled) { Text("Nuova ripresa") }
            Text("Grana e simulazione di ripresa sono regolazioni creative; non una calibrazione dell’ISP.",style=MaterialTheme.typography.bodySmall)
            if(s.noiseMode>=2) {
                Control("Dimensione grana",s.grainSize,0.5f..12f,enabled) { update(s.copy(grainSize=it)) }
                Control("Grana luminanza",s.grainLuma,0f..1.5f,enabled) { update(s.copy(grainLuma=it)) }
                Control("Grana colore",s.grainChroma,0f..1.5f,enabled) { update(s.copy(grainChroma=it)) }
                Control("Morbidezza grana",s.grainSoftness,0f..1f,enabled) { update(s.copy(grainSoftness=it)) }
                Control("Rinnovo grana / secondo",s.grainRate,1f..60f,enabled) { update(s.copy(grainRate=it)) }
                if(s.noiseMode==3) {
                    Control("Rumore di lettura aggiunto",s.readNoise,0f..0.02f,enabled) { update(s.copy(readNoise=it)) }
                    Control("Rumore nelle ombre",s.shadowGain,0f..3f,enabled) { update(s.copy(shadowGain=it)) }
                    Control("Protezione alte luci",s.highlightProtect,0f..1f,enabled) { update(s.copy(highlightProtect=it)) }
                    Control("Coerenza temporale",s.temporalCoherence,0f..1f,enabled) { update(s.copy(temporalCoherence=it)) }
                }
            }
        } }
    }
    item {
        Card { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("SVILUPPO E DETTAGLIO")
            Control("Esposizione EV",s.exposure,-3f..3f,enabled) { update(s.copy(exposure=it)) }
            Control("Temperatura",s.warmth,-1f..1f,enabled) { update(s.copy(warmth=it)) }
            Control("Saturazione",s.saturation,0f..2f,enabled) { update(s.copy(saturation=it)) }
            Control("Contrasto",s.contrast,0.5f..1.5f,enabled) { update(s.copy(contrast=it)) }
            Control("Denoise",s.denoise,0f..1f,enabled) { update(s.copy(denoise=it)) }
            Control("Nitidezza",s.sharpness,0f..1f,enabled) { update(s.copy(sharpness=it)) }
            Control("Vignettatura",s.vignette,0f..1f,enabled) { update(s.copy(vignette=it)) }
        } }
    }
    item {
        Card { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("COMPRESSIONE JPEG")
            Switch(checked=s.jpegEnabled,onCheckedChange={ update(s.copy(jpegEnabled=it)) },enabled=enabled,modifier=Modifier.testTag("jpeg-enabled"))
            Control("Intensità compressione",s.jpegAmount,0f..1f,enabled && s.jpegEnabled) { update(s.copy(jpegAmount=it)) }
            Text("DCT 8 × 8 e quantizzazione della variante JPEG originale. Artefatti su foto e video; il formato in uscita resta PNG o MP4.",style=MaterialTheme.typography.bodySmall)
        } }
    }
}
