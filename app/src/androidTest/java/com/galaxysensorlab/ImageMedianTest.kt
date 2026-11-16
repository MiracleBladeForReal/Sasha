package com.galaxysensorlab

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.runner.RunWith
import java.io.File

@UnstableApi
@RunWith(AndroidJUnit4::class)
class ImageMedianTest {
    @get:Rule val compose=androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val calibration=NoiseCalibration(context)
    private val settings=LabSettings(profileId="darktable:Canon:EOS R6",iso=100f,noiseSeed=123)

    @Test fun medianRemovesImpulseButPreservesStepEdgeAndBypass() {
        val source=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLACK)
        for(y in 0..63) for(x in 32..63) source.setPixel(x,y,Color.WHITE)
        source.setPixel(12,12,Color.WHITE)
        source.setPixel(48,12,Color.BLACK)
        val off=ImagePipeline.render(source,calibration,settings)
        for(radius in 1..2) {
            val filtered=ImagePipeline.render(source,calibration,settings.copy(medianRadius=radius))
            assertTrue(Color.red(filtered.getPixel(12,12))<20)
            assertTrue(Color.red(filtered.getPixel(48,12))>235)
            assertTrue(Color.red(filtered.getPixel(31,32))<20)
            assertTrue(Color.red(filtered.getPixel(32,32))>235)
            filtered.recycle()
        }
        assertTrue(Color.red(off.getPixel(12,12))>235)
        val bypass=ImagePipeline.render(source,calibration,settings.copy(medianRadius=2,original=true))
        assertTrue(Color.red(bypass.getPixel(12,12))>235)
        val split=ImagePipeline.render(source,calibration,settings.copy(medianRadius=2,compare=true))
        assertTrue(Color.red(split.getPixel(12,12))>235)
        assertTrue(Color.red(split.getPixel(48,12))>235)
        val saved=LabSettings.fromJson(settings.copy(medianRadius=2).toJson())
        assertEquals(2,saved.medianRadius)
        assertEquals(2,LabSettings(medianRadius=99).sanitizedDefects().medianRadius)
        listOf(source,off,bypass,split).forEach { it.recycle() }
    }

    @Test fun appImportsImageChangesMedianAndSavesPng() {
        val source=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK); setPixel(20,20,Color.WHITE)
        }
        val file=File.createTempFile("app-input", ".jpg",context.cacheDir)
        file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG,100,it) }; source.recycle()
        val target=File(context.cacheDir,"app-image-export.png").also { it.delete() }
        val ref=java.util.concurrent.atomic.AtomicReference<ImageLabViewModel>()
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText("Immagini").performClick()
            scenario.onActivity { activity ->
                val vm=androidx.lifecycle.ViewModelProvider(activity)[ImageLabViewModel::class.java]
                ref.set(vm); vm.load(Uri.fromFile(file),calibration,settings)
            }
            fun awaitDone(predicate: ()->Boolean) {
                val deadline=System.currentTimeMillis()+30_000
                var done=false
                while(!done && System.currentTimeMillis()<deadline) {
                    scenario.onActivity { done=predicate() }; Thread.sleep(100)
                }
                assertTrue(ref.get().message,done)
            }
            awaitDone { ref.get().source!=null && ref.get().preview!=null && !ref.get().rendering }
            scenario.onActivity { ref.get().save(Uri.fromFile(target),calibration,settings.copy(medianRadius=1,original=true,compare=true)) }
            awaitDone { !ref.get().working }
            assertEquals(Uri.fromFile(target),ref.get().result)
            val exported=ImagePipeline.decode(context,Uri.fromFile(target))
            assertEquals(64,exported.width); assertEquals(64,exported.height)
            assertTrue("PNG export must apply Median even when preview is original",Color.red(exported.getPixel(20,20))<30)
            exported.recycle()
        }
        file.delete(); target.delete()
    }

    @Test fun jpegPngWebpDecodeAndProcessedPngRetainsSizeOrientationAndAlpha() {
        val source=Bitmap.createBitmap(48,32,Bitmap.Config.ARGB_8888)
        for(y in 0 until 32) for(x in 0 until 48) source.setPixel(x,y,
            if(y<16) Color.argb(128,220,30,10) else Color.rgb(10,40,230))
        for(format in listOf(Bitmap.CompressFormat.JPEG,Bitmap.CompressFormat.PNG,Bitmap.CompressFormat.WEBP)) {
            val file=File.createTempFile("image", ".dat",context.cacheDir)
            file.outputStream().use { source.compress(format,100,it) }
            val decoded=ImagePipeline.decode(context,Uri.fromFile(file))
            val result=ImagePipeline.render(decoded,calibration,settings.copy(original=true))
            assertEquals(48,result.width); assertEquals(32,result.height)
            assertTrue("Top must stay red",Color.red(result.getPixel(20,4))>180)
            assertTrue("Bottom must stay blue",Color.blue(result.getPixel(20,26))>180)
            if(format==Bitmap.CompressFormat.PNG) assertEquals(128,Color.alpha(result.getPixel(20,4)))
            val out=File.createTempFile("export", ".png",context.cacheDir)
            out.outputStream().use { assertTrue(result.compress(Bitmap.CompressFormat.PNG,100,it)) }
            val reopened=ImagePipeline.decode(context,Uri.fromFile(out))
            assertEquals(result.getPixel(20,4),reopened.getPixel(20,4))
            listOf(decoded,result,reopened).forEach { it.recycle() }; file.delete(); out.delete()
        }
        source.recycle()
    }
}
