package com.galaxysensorlab

import android.graphics.Bitmap
import android.graphics.Color
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@UnstableApi
@RunWith(AndroidJUnit4::class)
class CompletePipelineTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val calibration=NoiseCalibration(context)
    private fun difference(a: Bitmap,b: Bitmap,left: Int=0,right: Int=a.width): Double {
        var sum=0.0; var n=0
        for(y in 4 until a.height-4) for(x in maxOf(4,left) until minOf(a.width-4,right)) {
            val p=a.getPixel(x,y); val q=b.getPixel(x,y)
            sum+=abs(Color.red(p)-Color.red(q))+abs(Color.green(p)-Color.green(q))+abs(Color.blue(p)-Color.blue(q)); n+=3
        }
        return sum/n
    }
    private fun input(): Bitmap=Bitmap.createBitmap(94,66,Bitmap.Config.ARGB_8888).apply {
        for(y in 0 until height) for(x in 0 until width) {
            val d=if((x/2+y/3)%2==0) 45 else -30
            setPixel(x,y,Color.argb(128,120+d+x/3,100-d+y/3,145+d))
        }
    }
    @Test fun restoredJpegChangesPhotoAndPreservesComparisonAlphaAndDisabledState() {
        val source=input(); val clean=LabSettings(noise=0f)
        val baseline=ImagePipeline.render(source,calibration,clean)
        val compressed=ImagePipeline.render(source,calibration,clean.copy(jpegEnabled=true,jpegAmount=1f))
        val mild=ImagePipeline.render(source,calibration,clean.copy(jpegEnabled=true,jpegAmount=0.05f))
        val zero=ImagePipeline.render(source,calibration,clean.copy(jpegEnabled=true,jpegAmount=0f))
        val split=ImagePipeline.render(source,calibration,clean.copy(jpegEnabled=true,jpegAmount=1f,compare=true,split=0.47f))
        assertTrue(difference(baseline,compressed)>3)
        assertTrue(difference(baseline,mild)<difference(baseline,compressed))
        assertTrue(difference(baseline,zero)<1)
        assertTrue(difference(baseline,split,0,source.width/3)<2)
        assertTrue(difference(baseline,split,source.width*2/3,source.width)>3)
        assertEquals(128,Color.alpha(compressed.getPixel(20,20)))
        listOf(source,baseline,compressed,mild,zero,split).forEach { it.recycle() }
    }
    @Test fun restoredDevelopmentControlsAndAllNoiseModesActuallyChangePixels() {
        val source=input(); val clean=LabSettings(noise=0f)
        val baseline=ImagePipeline.render(source,calibration,clean)
        for(settings in listOf(clean.copy(exposure=1f),clean.copy(warmth=1f),clean.copy(saturation=0f),
            clean.copy(contrast=1.5f),clean.copy(vignette=1f),clean.copy(sharpness=1f))) {
            val output=ImagePipeline.render(source,calibration,settings)
            assertTrue("Development control must affect the result: $settings",difference(baseline,output)>0.4)
            output.recycle()
        }
        // Adaptive denoise should act on low-contrast noise, while preserving the
        // high-contrast detail in the fixture used for development controls above.
        val noisy=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply {
            for(y in 0 until height) for(x in 0 until width) {
                val gray=120+(x*7+y*13)%9-4
                setPixel(x,y,Color.rgb(gray,gray,gray))
            }
        }
        val noisyBase=ImagePipeline.render(noisy,calibration,clean)
        val smooth=ImagePipeline.render(noisy,calibration,clean.copy(denoise=1f))
        fun variance(bitmap: Bitmap): Double {
            var sum=0.0; var squared=0.0; var n=0
            for(y in 4 until 60) for(x in 4 until 60) {
                val v=Color.red(bitmap.getPixel(x,y)).toDouble(); sum+=v; squared+=v*v; n++
            }
            return squared/n-(sum/n)*(sum/n)
        }
        assertTrue("Denoise must reduce low-contrast spatial noise",variance(smooth)<variance(noisyBase)*0.8)
        listOf(noisy,noisyBase,smooth).forEach { it.recycle() }
        for(mode in 0..3) {
            val output=ImagePipeline.render(source,calibration,LabSettings(noiseMode=mode,noise=1.5f,iso=3200f,grainSize=12f,grainChroma=0.6f))
            assertTrue("Noise mode $mode must add noise",difference(baseline,output)>2)
            output.recycle()
        }
        source.recycle(); baseline.recycle()
    }
}
