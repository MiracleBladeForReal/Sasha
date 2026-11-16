package com.galaxysensorlab

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@UnstableApi
class ImageLabViewModel(app: Application): AndroidViewModel(app) {
    var source by mutableStateOf<Bitmap?>(null); private set
    var preview by mutableStateOf<Bitmap?>(null); private set
    var working by mutableStateOf(false); private set
    var rendering by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var result by mutableStateOf<Uri?>(null); private set
    private var renderJob: Job?=null
    private val gpu=Mutex()

    fun load(uri: Uri, calibration: NoiseCalibration, settings: LabSettings) {
        if(working) return
        working=true; message=null; result=null
        viewModelScope.launch {
            try {
                val decoded=withContext(Dispatchers.IO) { ImagePipeline.decode(getApplication(),uri) }
                renderJob?.cancel()
                source=decoded; preview=null
                render(calibration,settings)
            } catch(e: Exception) {
                if(e is CancellationException) throw e
                message=e.message ?: "Non riesco ad aprire l’immagine."
            } catch(e: OutOfMemoryError) { message="Memoria insufficiente: prova un’immagine più piccola." }
            finally { working=false }
        }
    }

    fun render(calibration: NoiseCalibration, settings: LabSettings) {
        val input=source ?: return
        renderJob?.cancel()
        renderJob=viewModelScope.launch {
            var output: Bitmap?=null
            rendering=true
            try {
                delay(160)
                gpu.withLock { withContext(Dispatchers.Default) { output=ImagePipeline.render(input,calibration,settings) } }
                preview=output; output=null
            } catch(e: Exception) {
                if(e is CancellationException) throw e
                message=e.message ?: "Elaborazione immagine non riuscita."
            } catch(e: OutOfMemoryError) { message="Memoria insufficiente: prova un’immagine più piccola." }
            finally { output?.recycle(); if(currentCoroutineContext().isActive) rendering=false }
        }
    }

    fun save(uri: Uri, calibration: NoiseCalibration, settings: LabSettings) {
        val input=source ?: return
        if(working) return
        working=true; result=null; message=null
        viewModelScope.launch {
            var output: Bitmap?=null
            try {
                gpu.withLock { withContext(Dispatchers.Default) {
                    output=ImagePipeline.render(input,calibration,settings.copy(original=false,compare=false))
                } }
                withContext(Dispatchers.IO) {
                    requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri,"w")).use {
                        check(requireNotNull(output).compress(Bitmap.CompressFormat.PNG,100,it))
                    }
                }
                result=uri; message="Immagine PNG elaborata salvata."
            } catch(e: Exception) {
                if(e is CancellationException) throw e
                message="Salvataggio immagine non riuscito. Controlla spazio e destinazione."
            } catch(e: OutOfMemoryError) { message="Memoria insufficiente per esportare questa immagine." }
            finally { output?.recycle(); working=false }
        }
    }
}
