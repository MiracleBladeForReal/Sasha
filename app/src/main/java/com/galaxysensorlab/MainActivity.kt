package com.galaxysensorlab

import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var videoUri by remember { mutableStateOf<Uri?>(null) }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    videoUri = uri
                }
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Galaxy Sensor Lab", style = MaterialTheme.typography.headlineMedium)
                    Text("MP4 preview · first prototype")
                    Button(onClick = { picker.launch("video/*") }) { Text("Import video") }
                    val currentUri = videoUri
                    if (currentUri != null) {
                        key(currentUri) {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            val player = remember(currentUri) {
                                ExoPlayer.Builder(context).build().apply {
                                    setMediaItem(MediaItem.fromUri(currentUri))
                                    prepare()
                                    playWhenReady = false
                                }
                            }
                            DisposableEffect(player) { onDispose { player.release() } }
                            AndroidView(
                                factory = { ctx -> PlayerView(ctx).apply { this.player = player } },
                                modifier = Modifier.fillMaxWidth().weight(1f)
                            )
                        }
                    } else {
                        Text("Choose an MP4 video to preview it here.")
                    }
                    Text("Sensor-noise and ISP simulation will be added in future versions.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
