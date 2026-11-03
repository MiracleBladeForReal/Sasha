package com.galaxysensorlab

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Real Android codec + GL shader tests. No mocked pixels or exporters. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class SensorPipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val calibration = NoiseCalibration(context)

    @Test fun publishedCalibrationAndIsoBoundsArePreserved() {
        assertEquals(6, calibration.points.size)
        assertEquals(0.0004794565f, calibration.at(100f).shot[1], 1e-10f)
        assertEquals(0.0199304065f, calibration.at(3200f).shot[1], 1e-9f)
        assertEquals(calibration.at(100f).shot[1], calibration.at(1f).shot[1], 0f)
        assertEquals(calibration.at(3200f).shot[1], calibration.at(99999f).shot[1], 0f)
        for (point in calibration.points) assertEquals(0f, point.read[1], 0f)
        assertTrue(calibration.sigma(3200f, 0.18f) > calibration.sigma(100f, 0.18f))
    }

    @Test fun darktableCatalogRetainsCoefficientsAndBounds() {
        assertEquals(439, calibration.cameras.size)
        val id = "darktable:Canon:EOS R6"
        val point = calibration.at(800f, id)
        assertEquals(0.00000425892075056399f, point.shot[1], 1e-12f)
        assertEquals(9.20342702749297e-9f, point.read[1], 1e-15f)
        val a = calibration.at(100f, id)
        val b = calibration.at(125f, id)
        val mid = calibration.at(112.5f, id)
        assertEquals((a.shot[1]+b.shot[1])*0.5f, mid.shot[1], 1e-12f)
        assertEquals(calibration.at(50f,id).shot[1], calibration.at(1f,id).shot[1], 0f)
        assertTrue(calibration.search("oneplus nord 3").any { it.model == "OnePlus Nord 3 5G" })
        assertTrue(calibration.search("samsung nx1").any { it.model == "NX1" })
        assertEquals(NoiseCalibration.DEFAULT_PROFILE, calibration.profile("missing").id)
        for (camera in calibration.cameras) {
            assertTrue(camera.points.zipWithNext().all { (p,q) -> p.iso < q.iso })
            assertTrue(calibration.sigma(camera.minIso, 0f, camera.id).isFinite())
            assertTrue(calibration.sigma(camera.maxIso, 0.18f, camera.id).isFinite())
        }
    }

    @Test fun migrationDropsAllCreativeSettings() {
        val migrated=LabSettings.fromJson("""{"iso":1600,"noiseSeed":632,"noise":2,"readNoise":0.02,"shadowGain":3,"highlightProtect":1,"temporalCoherence":1,"grainSize":12,"grainLuma":1.5,"grainChroma":1.5,"grainSoftness":1,"grainRate":1,"exposure":3,"warmth":1,"contrast":1.5,"saturation":2,"denoise":1,"sharpness":1,"vignette":1}""")
        assertEquals(LabSettings(iso=1600f,noiseSeed=632),migrated)
        assertEquals(setOf("schema","profileId","iso","noiseSeed"),org.json.JSONObject(migrated.toJson()).keys().asSequence().toSet())
        assertEquals(migrated,LabSettings.fromJson(migrated.copy(original=true,compare=true).toJson()))
    }

    @Test fun sameRealizationRepeatsAndNewRealizationChangesPixels() {
        val input=createInput(flat=true)
        val seed=NoiseSeeds.next()
        val settings=LabSettings(iso=1600f,noiseSeed=seed)
        val a=render(input,settings)
        val b=render(input,settings)
        val c=render(input,settings.copy(noiseSeed=NoiseSeeds.next(seed)))
        assertTrue(meanDifference(a,b)<2)
        assertTrue(meanDifference(a,c)>3)
        listOf(a,b,c).forEach { it.recycle() }; input.delete()
    }

    @Test fun publishedIsoAndCameraChangeActualNoise() {
        val input=createInput(flat=true)
        val base=render(input,LabSettings(original=true))
        val low=render(input,LabSettings(iso=100f,noiseSeed=719))
        val high=render(input,LabSettings(iso=1600f,noiseSeed=719))
        val canon=render(input,LabSettings(profileId="darktable:Canon:EOS R6",iso=1600f,noiseSeed=719))
        assertTrue("Published ISO must increase exported noise",meanDifference(base,high)>meanDifference(base,low)*1.8)
        assertTrue("Actual sensor profile must change noise",meanDifference(base,high)>meanDifference(base,canon)+3)
        listOf(base,low,high,canon).forEach { it.recycle() }; input.delete()
    }

    @Test fun s6ZeroReadOffsetKeepsPureBlackBlack() {
        val input=createInput(flat=true,level=0)
        val base=render(input,LabSettings(original=true))
        val sensor=render(input,LabSettings(iso=3200f,noiseSeed=314))
        assertTrue("No artificial read floor or shadow lift",meanDifference(base,sensor)<1)
        assertEquals(0f,calibration.at(3200f).read[1],0f)
        base.recycle(); sensor.recycle(); input.delete()
    }

    @Test fun addingNoiseDoesNotApplyAnExposureOrToneCurve() {
        val input=createInput(flat=true,level=100)
        val base=render(input,LabSettings(original=true))
        val sensor=render(input,LabSettings(iso=800f,noiseSeed=1428))
        fun meanLinear(bitmap: Bitmap): Double {
            var sum=0.0; var n=0
            for(y in 12 until bitmap.height-12 step 2) for(x in 12 until bitmap.width-12 step 2) {
                sum+=Math.pow(Color.red(bitmap.getPixel(x,y))/255.0,2.2); n++
            }
            return sum/n
        }
        assertTrue("Linear mean must stay close to the source",abs(meanLinear(base)-meanLinear(sensor))<0.008)
        assertTrue("Noise must still be present",meanDifference(base,sensor)>2)
        base.recycle(); sensor.recycle(); input.delete()
    }

    @Test fun photonVarianceGrowsWithLinearBrightness() {
        val dark=createInput(flat=true,level=60)
        val bright=createInput(flat=true,level=160)
        val settings=LabSettings(iso=400f,noiseSeed=682)
        val a=render(dark,settings)
        val b=render(bright,settings)
        val va=approximateLinearVariance(a); val vb=approximateLinearVariance(b)
        assertTrue("Photon variance must grow with signal: dark=$va bright=$vb",vb>va*1.5)
        a.recycle(); b.recycle(); dark.delete(); bright.delete()
    }

    @Test fun comparisonOnlyChangesRightSide() {
        val input=createInput(flat=true)
        val base=render(input,LabSettings(original=true))
        val split=render(input,LabSettings(iso=3200f,noiseSeed=93,compare=true))
        assertTrue(meanDifference(base,split,0,base.width/3)<2)
        assertTrue(meanDifference(base,split,base.width*2/3,base.width)>3)
        base.recycle(); split.recycle(); input.delete()
    }

    @Test fun hdrCannotSilentlyChangeBrightness() {
        try {
            SensorEffect(calibration,LabSettings()).toGlShaderProgram(context,true)
            fail("HDR must be rejected instead of implicitly tone mapped")
        } catch(expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("SDR"))
        }
    }

    @Test fun appOpensVideoAndSavesProcessedResult() {
        assertEquals("Third app must install alongside versions 0.2 and 0.3", "com.galaxysensorlab.edgecapture", context.packageName)
        val png = createInput()
        val source = File(context.cacheDir, "ui-source.mp4")
        render(png, LabSettings(original = true), source).recycle()
        val destination = File(context.cacheDir, "ui-export.mp4").also { it.delete() }
        val ready = CountDownLatch(1)
        val firstFrame = CountDownLatch(1)
        val ref = AtomicReference<LabViewModel>()
        var retainedSeed = -1
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[LabViewModel::class.java]
                ref.set(model)
                model.player.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { firstFrame.countDown() }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) ready.countDown()
                    }
                })
                model.loadVideo(Uri.fromFile(source))
            }
            assertTrue("The actual player must open the clip", ready.await(20, TimeUnit.SECONDS))
            assertTrue("Player must render the first frame, not only become ready",firstFrame.await(30,TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                ref.get().player.pause()
                ref.get().update(LabSettings(original = true))
            }
            Thread.sleep(700)
            val before = instrumentation.uiAutomation.takeScreenshot()
            scenario.onActivity { ref.get().update(LabSettings(iso = 3200f,noiseSeed = 6187)) }
            // GPU compilation/rendering can outlive STATE_READY on the software emulator.
            // Poll actual pixels with a bounded deadline instead of assuming a 700 ms redraw.
            val redrawDeadline = System.currentTimeMillis()+15_000
            var screenshot = instrumentation.uiAutomation.takeScreenshot()
            var delta = previewDifference(before,screenshot)
            while (delta <= 2.0 && System.currentTimeMillis() < redrawDeadline) {
                screenshot.recycle()
                Thread.sleep(150)
                screenshot = instrumentation.uiAutomation.takeScreenshot()
                delta = previewDifference(before,screenshot)
            }
            saveScreenshot(before,"preview-before.png")
            saveScreenshot(screenshot,"preview-after.png")
            assertTrue("Paused preview must redraw after changing sensor profile settings: delta=$delta",delta > 2.0)
            before.recycle()
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "lab-video.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GalaxySensorLab-Edge-Capture-tests")
                }
                val imageUri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                requireNotNull(context.contentResolver.openOutputStream(imageUri)).use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            screenshot.recycle()
            scenario.onActivity {
                val model = ref.get()
                val seedBefore = model.settings.noiseSeed
                model.newTake()
                assertNotEquals("New take UI action must change the sequence",seedBefore,model.settings.noiseSeed)
                retainedSeed = model.settings.noiseSeed
                model.update(LabSettings(iso = 1600f, noiseSeed = retainedSeed, original = true, compare = true))
                model.export(Uri.fromFile(destination))
            }
            val deadline = System.currentTimeMillis() + 60000
            var isBusy = true
            while (isBusy && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
                scenario.onActivity { isBusy = ref.get().busy }
            }
            scenario.onActivity {
                assertFalse("Actual app export must complete", ref.get().busy)
                assertEquals(ref.get().message, Uri.fromFile(destination), ref.get().resultUri)
            }
            assertTrue(destination.length() > 1000)
            val reader=MediaMetadataRetriever()
            try {
                reader.setDataSource(destination.absolutePath)
                assertEquals("No resize", "320",reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                assertEquals("No resize", "240",reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
                val frame=requireNotNull(reader.getFrameAtTime(400_000,MediaMetadataRetriever.OPTION_CLOSEST))
                val reference=render(png,LabSettings(original=true))
                assertTrue("Export must apply the full sensor even while preview is bypassed",meanDifference(reference,frame)>2)
                frame.recycle(); reference.recycle()
            } finally { reader.release() }
        }
        val reopened = CountDownLatch(1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[LabViewModel::class.java]
                model.player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) reopened.countDown()
                    }
                })
                if (model.player.playbackState == Player.STATE_READY) reopened.countDown()
            }
            assertTrue("Saved clip must reopen",reopened.await(20,TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[LabViewModel::class.java]
                assertEquals("Reopening must preserve the selected take",retainedSeed,model.settings.noiseSeed)
            }
        }
        png.delete(); source.delete(); destination.delete()
        context.getSharedPreferences("lab", 0).edit().clear().commit()
    }

    private fun previewDifference(a: Bitmap,b: Bitmap): Double {
        var total=0.0; var count=0
        for(y in b.height/5 until b.height/3 step 4) for(x in b.width/3 until b.width*2/3 step 4) {
            total+=abs(Color.red(a.getPixel(x,y))-Color.red(b.getPixel(x,y))); count++
        }
        return total/count
    }

    private fun saveScreenshot(bitmap: Bitmap,name: String) {
        if(Build.VERSION.SDK_INT < 29) return
        val values=ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,name)
            put(MediaStore.Images.Media.MIME_TYPE,"image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/GalaxySensorLab-Edge-Capture-tests")
        }
        val uri=requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values))
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }

    private fun approximateLinearVariance(bitmap: Bitmap): Double {
        var sum=0.0; var squared=0.0; var n=0
        for(y in 12 until bitmap.height-12 step 2) for(x in 12 until bitmap.width-12 step 2) {
            // Inverse display gamma approximation; used only to compare broad variance ranges.
            val v=Math.pow(Color.red(bitmap.getPixel(x,y))/255.0,2.2)
            sum+=v; squared+=v*v; n++
        }
        return squared/n-(sum/n)*(sum/n)
    }

    private fun createInput(flat: Boolean = false, level: Int = 100): File {
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val gray = if (flat) level else 60 + x * 70 / bitmap.width
            bitmap.setPixel(x, y, Color.rgb(gray, if (flat) gray else gray + y * 30 / bitmap.height, gray))
        }
        return File.createTempFile("input", ".png", context.cacheDir).also { file ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun render(input: File, settings: LabSettings, retainedOutput: File? = null, sampleTimeUs: Long = 400_000): Bitmap {
        val output = File.createTempFile("test", ".mp4", context.cacheDir).also { it.delete() }
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = AtomicReference<Transformer?>()
        instrumentation.runOnMainSync {
            val media = MediaItem.Builder().setUri(Uri.fromFile(input))
                .setMimeType(MimeTypes.IMAGE_PNG).setImageDurationMs(1000).build()
            val edited = EditedMediaItem.Builder(media).setFrameRate(24)
                .setEffects(Effects(emptyList(), listOf(SensorEffect(calibration, settings)))).build()
            val encoders = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(4_000_000).build()).build()
            val transformer = Transformer.Builder(context).setEncoderFactory(encoders).setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.countDown() }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        failure.set(exportException); done.countDown()
                    }
                }).build()
            worker.set(transformer)
            try { transformer.start(edited, output.absolutePath) }
            catch (e: Exception) { failure.set(e); done.countDown() }
        }
        try {
            assertTrue("Export timed out", done.await(90, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Real GPU/codec export failed", it) }
            assertTrue(output.length() > 1000)
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(output.absolutePath)
                assertTrue((reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0) >= 800)
                retainedOutput?.let { output.copyTo(it, overwrite = true) }
                return requireNotNull(reader.getFrameAtTime(sampleTimeUs, MediaMetadataRetriever.OPTION_CLOSEST))
            } finally { reader.release() }
        } finally {
            instrumentation.runOnMainSync { worker.get()?.cancel() }
            output.delete()
        }
    }

    private fun meanDifference(a: Bitmap, b: Bitmap, left: Int = 0, right: Int = a.width): Double {
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        var total = 0.0
        var count = 0
        for (y in 8 until a.height - 8 step 2) for (x in maxOf(8, left) until minOf(a.width - 8, right) step 2) {
            val p = a.getPixel(x, y); val q = b.getPixel(x, y)
            total += abs(Color.red(p) - Color.red(q)) + abs(Color.green(p) - Color.green(q)) + abs(Color.blue(p) - Color.blue(q))
            count += 3
        }
        return total / count
    }
}
