package com.galaxysensorlab

import android.app.Application
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class LabViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("lab", 0)
    val calibration = NoiseCalibration(application)
    var settings by mutableStateOf(runCatching {
        LabSettings.fromJson(prefs.getString("settings", "{}") ?: "{}")
    }.getOrDefault(LabSettings()))
        private set
    private val effect = SensorEffect(calibration, settings)
    val player = ExoPlayer.Builder(application).build().apply {
        setVideoEffects(listOf(effect))
        repeatMode = Player.REPEAT_MODE_ONE
        addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                message = "Impossibile riprodurre questo video (${error.errorCodeName}). Prova un MP4 H.264 SDR."
            }
        })
    }
    var videoUri by mutableStateOf<Uri?>(null)
        private set
    var videoName by mutableStateOf("Nessun video selezionato")
        private set
    var videoInfo by mutableStateOf("")
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var progress by mutableStateOf<Int?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    var resultUri by mutableStateOf<Uri?>(null)
        private set
    var exportHeight by mutableStateOf(1080)
    private var sourceHeight = 1080
    private var transformer: Transformer? = null
    private var outputFile: File? = null
    private var destination: Uri? = null
    private var progressJob: Job? = null
    private var refreshJob: Job? = null
    private var loadJob: Job? = null

    init {
        prefs.getString("video", null)?.let { loadVideo(Uri.parse(it)) }
    }

    fun update(value: LabSettings) {
        settings = value
        effect.settings = value
        prefs.edit().putString("settings", value.toJson()).apply()
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            delay(100)
            if (videoUri != null && !player.isPlaying) {
                // ExoPlayer ignores seeks to the same millisecond. A 1 ms seek flushes the
                // paused frame through the shader again without visibly advancing the clip.
                val position = player.currentPosition
                val next = if (player.duration > 1 && position >= player.duration - 1)
                    (position - 1).coerceAtLeast(0) else position + 1
                player.seekTo(next)
            }
        }
    }

    fun loadVideo(uri: Uri) {
        if (busy) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            runCatching {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                val info = withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else "Video" } ?: "Video"
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(app, uri)
                        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        require(w > 0 && h > 0) { "Il file non contiene una traccia video." }
                        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        val displayHeight = if (rotation % 180 == 0) h else w
                        Triple(name, "${w} × ${h} · ${duration / 1000}s", displayHeight)
                    } finally { retriever.release() }
                }
                videoUri = uri
                videoName = info.first
                videoInfo = info.second
                sourceHeight = info.third
                resultUri = null
                message = null
                player.setMediaItem(MediaItem.fromUri(uri))
                player.prepare()
                player.playWhenReady = true
                prefs.edit().putString("video", uri.toString()).apply()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                message = "Non riesco ad aprire il video. Seleziona un file locale leggibile."
            }
        }
    }

    fun export(uri: Uri) {
        val input = videoUri ?: return
        if (busy) return
        player.pause()
        busy = true; saving = false; progress = null; resultUri = null; message = null
        destination = uri
        val app = getApplication<Application>()
        val file = File.createTempFile("galaxy-", ".mp4", app.cacheDir).also { it.delete() }
        outputFile = file
        val exportSettings = settings.copy(original = false, compare = false)
        val effects = mutableListOf<Effect>(SensorEffect(calibration, exportSettings))
        val targetHeight = minOf(sourceHeight, exportHeight).coerceAtLeast(2)
        effects.add(Presentation.createForHeight(targetHeight - targetHeight % 2))
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(input))
            .setEffects(Effects(emptyList(), effects)).build()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(edited).build())
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build()
        try {
            val worker = Transformer.Builder(app)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        progressJob?.cancel()
                        transformer = null
                        saving = true
                        viewModelScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    val output = app.contentResolver.openOutputStream(uri, "w")
                                        ?: error("Destinazione non scrivibile")
                                    output.use { out -> file.inputStream().use { it.copyTo(out) } }
                                }
                                resultUri = uri
                                message = "MP4 elaborato salvato."
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                message = "Esportazione riuscita, ma salvataggio non riuscito. Controlla spazio e destinazione e riprova."
                            } finally {
                                file.delete(); outputFile = null; destination = null
                                busy = false; saving = false; progress = null
                            }
                        }
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        cleanupExport()
                        message = "Esportazione non riuscita (${exportException.errorCode}). Prova 720p o un video SDR."
                    }
                }).build()
            transformer = worker
            worker.start(composition, file.absolutePath)
            progressJob = viewModelScope.launch {
                val holder = ProgressHolder()
                while (busy && transformer != null) {
                    progress = if (worker.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else null
                    delay(300)
                }
            }
        } catch (e: Exception) {
            cleanupExport()
            message = "Non riesco ad avviare l’esportazione. Controlla formato e spazio disponibile."
        }
    }

    fun cancelExport() {
        if (saving) return
        transformer?.cancel()
        cleanupExport()
        message = "Esportazione annullata."
    }

    private fun cleanupExport() {
        progressJob?.cancel(); transformer = null
        outputFile?.delete(); outputFile = null
        destination?.let { uri ->
            runCatching { getApplication<Application>().contentResolver.delete(uri, null, null) }
        }
        destination = null; busy = false; saving = false; progress = null
    }

    fun clearMessage() { message = null }

    override fun onCleared() {
        transformer?.cancel()
        if (busy && !saving) cleanupExport()
        player.release()
        super.onCleared()
    }
}
