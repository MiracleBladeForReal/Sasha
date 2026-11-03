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
        val b = calibration.at(200f, id)
        val mid = calibration.at(150f, id)
        assertEquals((a.shot[1]+b.shot[1])*0.5f, mid.shot[1], 1e-12f)
        assertEquals(a.shot[1], calibration.at(1f,id).shot[1], 0f)
        assertTrue(calibration.search("oneplus nord 3").any { it.model == "OnePlus Nord 3 5G" })
        assertTrue(calibration.search("samsung nx1").any { it.model == "NX1" })
        assertEquals(NoiseCalibration.DEFAULT_PROFILE, calibration.profile("missing").id)
        for (camera in calibration.cameras) {
            assertTrue(camera.points.zipWithNext().all { (p,q) -> p.iso < q.iso })
            assertTrue(calibration.sigma(camera.minIso, 0f, camera.id).isFinite())
            assertTrue(calibration.sigma(camera.maxIso, 0.18f, camera.id).isFinite())
        }
    }

    @Test fun cameraSelectionChangesTheActualExportNoise() {
        val input = createInput(flat = true)
        val baseSettings = LabSettings(noise = 0f)
        val base = render(input, baseSettings)
        val s6 = render(input, baseSettings.copy(noise = 1.5f, iso = 800f, grainChroma = 0f))
        val canon = render(input, baseSettings.copy(profileId = "darktable:Canon:EOS R6", noise = 1.5f, iso = 800f, grainChroma = 0f))
        assertTrue("Camera selection must change exported pixels, not only labels",
            meanDifference(base,s6) > meanDifference(base,canon) + 3)
        base.recycle(); s6.recycle(); canon.recycle(); input.delete()
    }

    @Test fun settingsSurviveSavingWithoutPreviewBypass() {
        val original = LabSettings(profileId = "darktable:Canon:EOS R6", iso = 6400f, noise = 1.2f, exposure = -1f, denoise = 0.4f,
            naturalGrain = false, grainSize = 8.5f, grainLuma = 0.4f, grainChroma = 0.8f,
            grainSoftness = 0.3f, grainRate = 12f,
            compare = true, original = true)
        val restored = LabSettings.fromJson(original.toJson())
        assertEquals(original.copy(compare = false, original = false), restored)
    }

    @Test fun realShaderExportsPlayableMp4AndChangesPixels() {
        val input = createInput()
        val baseline = render(input, LabSettings(noise = 0f))
        val brighter = render(input, LabSettings(noise = 0f, exposure = 1.5f))
        val noisy = render(input, LabSettings(iso = 3200f, noise = 2f))
        val bypass = render(input, LabSettings(noise = 2f, exposure = 2f, original = true))
        val denoised = render(input, LabSettings(iso = 3200f, noise = 2f, denoise = 1f))
        assertTrue("Exposure must change actual encoded pixels", meanDifference(baseline, brighter) > 12)
        assertTrue("SIDD noise must change actual encoded pixels", meanDifference(baseline, noisy) > 2)
        assertTrue("Original bypass must preserve pixels", meanDifference(baseline, bypass) < 3)
        assertTrue("Denoising must reduce deviation from the clean input",
            meanDifference(baseline, denoised) < meanDifference(baseline, noisy))
        listOf(baseline, brighter, noisy, bypass, denoised).forEach { it.recycle() }
        input.delete()
    }

    @Test fun comparisonOnlyProcessesRightSide() {
        val input = createInput()
        val base = render(input, LabSettings(noise = 0f))
        val split = render(input, LabSettings(noise = 0f, exposure = 1.5f, compare = true, split = 0.5f))
        assertTrue(meanDifference(base, split, 0, base.width / 3) < 3)
        assertTrue(meanDifference(base, split, base.width * 2 / 3, base.width) > 12)
        base.recycle(); split.recycle(); input.delete()
    }

    @Test fun appOpensVideoAndSavesProcessedResult() {
        assertEquals("New app must install alongside version 0.2", "com.galaxysensorlab.grain", context.packageName)
        val png = createInput()
        val source = File(context.cacheDir, "ui-source.mp4")
        render(png, LabSettings(noise = 0f), source).recycle()
        val destination = File(context.cacheDir, "ui-export.mp4").also { it.delete() }
        val ready = CountDownLatch(1)
        val ref = AtomicReference<LabViewModel>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[LabViewModel::class.java]
                ref.set(model)
                model.player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) ready.countDown()
                    }
                })
                model.loadVideo(Uri.fromFile(source))
            }
            assertTrue("The actual player must open the clip", ready.await(20, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                ref.get().player.pause()
                ref.get().update(LabSettings(noise = 0f))
            }
            Thread.sleep(700)
            val before = instrumentation.uiAutomation.takeScreenshot()
            scenario.onActivity { ref.get().update(LabSettings(noise = 0f, exposure = 1.5f)) }
            Thread.sleep(700)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            // Sample only the middle of the video area, excluding labels and controls.
            var previewDelta = 0.0
            var samples = 0
            for (y in screenshot.height / 5 until screenshot.height / 3 step 4) {
                for (x in screenshot.width / 3 until screenshot.width * 2 / 3 step 4) {
                    previewDelta += abs(Color.red(screenshot.getPixel(x, y)) - Color.red(before.getPixel(x, y)))
                    samples++
                }
            }
            assertTrue("Paused preview must redraw after changing exposure", previewDelta / samples > 6.0)
            before.recycle()
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "lab-video.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GalaxySensorLab-Grain-tests")
                }
                val imageUri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                requireNotNull(context.contentResolver.openOutputStream(imageUri)).use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            screenshot.recycle()
            scenario.onActivity {
                val model = ref.get()
                model.update(LabSettings(noise = 0.5f, exposure = 0.5f))
                model.exportHeight = 720
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
        }
        png.delete(); source.delete(); destination.delete()
        context.getSharedPreferences("lab", 0).edit().clear().commit()
    }

    @Test fun naturalGrainSizeAndChromaChangeActualExportedPixels() {
        val input = createInput(flat = true)
        val base = render(input, LabSettings(noise = 0f))
        val mono = LabSettings(iso = 3200f, noise = 1.4f, naturalGrain = true,
            grainSize = 0.5f, grainChroma = 0f, grainSoftness = 1f)
        val fine = render(input, mono)
        val coarse = render(input, mono.copy(grainSize = 12f))
        val coloured = render(input, mono.copy(grainLuma = 0f, grainChroma = 1f))
        assertTrue("Natural grain must affect the video", meanDifference(base, fine) > 3)
        assertTrue("Larger grain must have greater spatial correlation", roughness(coarse) < roughness(fine) * 0.9)
        assertTrue("Colour control must add chroma variation", colourVariation(coloured) > colourVariation(fine) + 3)
        listOf(base, fine, coarse, coloured).forEach { it.recycle() }
        input.delete()
    }

    @Test fun classicModeIgnoresTheNewCreativeControls() {
        val input = createInput()
        val classic = LabSettings(iso = 1600f, noise = 1f, naturalGrain = false)
        val a = render(input, classic)
        val b = render(input, classic.copy(grainSize = 12f, grainLuma = 0f,
            grainChroma = 1.5f, grainSoftness = 0f, grainRate = 1f))
        assertTrue("Turning natural grain off must restore the classic noise path", meanDifference(a,b) < 3)
        a.recycle(); b.recycle(); input.delete()
    }

    @Test fun grainRenewalFollowsVideoTimeRatherThanPlaybackSpeed() {
        val input = createInput(flat = true)
        val slow = LabSettings(iso = 3200f, noise = 1.4f, grainRate = 1f, grainChroma = 0f)
        val slowA = render(input, slow, sampleTimeUs = 100_000)
        val slowB = render(input, slow, sampleTimeUs = 400_000)
        val fastA = render(input, slow.copy(grainRate = 24f), sampleTimeUs = 100_000)
        val fastB = render(input, slow.copy(grainRate = 24f), sampleTimeUs = 400_000)
        assertTrue("One renewal per second must preserve the pattern within that second", meanDifference(slowA,slowB) < 3)
        assertTrue("24 renewals per second must change the pattern", meanDifference(fastA,fastB) > 3)
        listOf(slowA,slowB,fastA,fastB).forEach { it.recycle() }
        input.delete()
    }

    private fun roughness(bitmap: Bitmap): Double {
        var total = 0.0; var count = 0
        for (y in 12 until bitmap.height-12) for (x in 12 until bitmap.width-13) {
            total += abs(Color.red(bitmap.getPixel(x,y)) - Color.red(bitmap.getPixel(x+1,y)))
            count++
        }
        return total / count
    }

    private fun colourVariation(bitmap: Bitmap): Double {
        var total = 0.0; var count = 0
        for (y in 12 until bitmap.height-12 step 2) for (x in 12 until bitmap.width-12 step 2) {
            val p = bitmap.getPixel(x,y)
            total += abs(Color.red(p)-Color.green(p)) + abs(Color.blue(p)-Color.green(p))
            count += 2
        }
        return total/count
    }

    private fun createInput(flat: Boolean = false): File {
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val gray = if (flat) 100 else 60 + x * 70 / bitmap.width
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
