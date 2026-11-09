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

    @Test fun settingsSurviveSavingWithoutPreviewBypass() {
        val original = LabSettings(iso = 1600f, noise = 1.2f, exposure = -1f, denoise = 0.4f,
            jpegEnabled = true, jpegAmount = 0.73f, compare = true, original = true)
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

    @Test fun jpegDefaultsAndBoundsRemainCompatible() {
        val old = LabSettings.fromJson("{}")
        assertFalse(old.jpegEnabled)
        assertEquals(0.5f, old.jpegAmount, 0f)
        assertEquals(1f, LabSettings.fromJson("{\"jpegAmount\":7}").jpegAmount, 0f)
        assertEquals(0f, LabSettings.fromJson("{\"jpegAmount\":-2}").jpegAmount, 0f)
    }

    @Test fun jpegChangesRealPixelsAndHonorsZeroBypassAndComparison() {
        val input = createDetailedInput()
        val clean = LabSettings(noise = 0f)
        val jpeg = clean.copy(jpegEnabled = true, jpegAmount = 1f)
        val base = render(input, clean)
        val compressed = render(input, jpeg)
        val mild = render(input, jpeg.copy(jpegAmount = 0.05f))
        val zero = render(input, jpeg.copy(jpegAmount = 0f))
        val disabled = render(input, jpeg.copy(jpegEnabled = false))
        val original = render(input, jpeg.copy(original = true, exposure = 1.5f))
        val split = render(input, jpeg.copy(compare = true, split = 0.47f))
        assertTrue("JPEG must degrade actual exported detail", meanDifference(base, compressed) > 3)
        assertTrue("Mild JPEG must preserve more detail than strong JPEG",
            meanDifference(base, mild) < meanDifference(base, compressed))
        assertTrue("Zero must bypass JPEG", meanDifference(base, zero) < 3)
        assertTrue("Disabled JPEG must bypass", meanDifference(base, disabled) < 3)
        assertTrue("Original must bypass JPEG and development", meanDifference(base, original) < 3)
        assertTrue("Comparison left must preserve the original", meanDifference(base, split, 0, base.width / 3) < 3)
        assertTrue("Comparison right must contain JPEG", meanDifference(base, split, base.width * 2 / 3) > 3)
        listOf(base, compressed, mild, zero, disabled, original, split).forEach { it.recycle() }
        input.delete()
    }

    @Test fun jpegHandlesPartialBlocksAtTheImageEdges() {
        val input = createDetailedInput(318, 238)
        val bitmap = render(input, LabSettings(noise = 0f, jpegEnabled = true, jpegAmount = 0.8f))
        assertEquals(318, bitmap.width)
        assertEquals(238, bitmap.height)
        assertTrue("Padded edge must not turn black", Color.red(bitmap.getPixel(317, 237)) > 10)
        bitmap.recycle(); input.delete()
    }

    @Test fun appOpensVideoAndSavesProcessedResult() {
        val png = createDetailedInput()
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
            var screenshot = instrumentation.uiAutomation.takeScreenshot()
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
            scenario.onActivity {
                ref.get().update(LabSettings(noise = 0f, exposure = 1.5f,
                    jpegEnabled = true, jpegAmount = 1f))
            }
            Thread.sleep(700)
            val jpegPreview = instrumentation.uiAutomation.takeScreenshot()
            var jpegDelta = 0.0
            var jpegSamples = 0
            for (y in jpegPreview.height / 5 until jpegPreview.height / 3 step 4) {
                for (x in jpegPreview.width / 3 until jpegPreview.width * 2 / 3 step 4) {
                    val p = jpegPreview.getPixel(x, y)
                    val q = screenshot.getPixel(x, y)
                    jpegDelta += abs(Color.red(p) - Color.red(q)) +
                        abs(Color.green(p) - Color.green(q)) + abs(Color.blue(p) - Color.blue(q))
                    jpegSamples += 3
                }
            }
            assertTrue("Paused preview must redraw after enabling JPEG", jpegDelta / jpegSamples > 2.0)
            screenshot.recycle()
            screenshot = jpegPreview
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "lab-video.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GalaxySensorLab-tests")
                }
                val imageUri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                requireNotNull(context.contentResolver.openOutputStream(imageUri)).use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            screenshot.recycle()
            scenario.onActivity {
                val model = ref.get()
                model.update(LabSettings(noise = 0.5f, exposure = 0.5f, jpegEnabled = true, jpegAmount = 0.8f))
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

    private fun createDetailedInput(w: Int = 320, h: Int = 240): File {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            val detail = if ((x / 2 + y / 3) % 2 == 0) 55 else -35
            val red = (100 + x * 70 / w + detail).coerceIn(0, 255)
            val green = (110 + y * 50 / h - detail).coerceIn(0, 255)
            bitmap.setPixel(x, y, Color.rgb(red, green, 140 + detail))
        }
        return File.createTempFile("jpeg-detail", ".png", context.cacheDir).also { file ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun createInput(): File {
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val gray = 60 + x * 70 / bitmap.width
            bitmap.setPixel(x, y, Color.rgb(gray, gray + y * 30 / bitmap.height, gray))
        }
        return File.createTempFile("input", ".png", context.cacheDir).also { file ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun render(input: File, settings: LabSettings, retainedOutput: File? = null): Bitmap {
        val output = File.createTempFile("test", ".mp4", context.cacheDir).also { it.delete() }
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = AtomicReference<Transformer?>()
        instrumentation.runOnMainSync {
            val media = MediaItem.Builder().setUri(Uri.fromFile(input))
                .setMimeType(MimeTypes.IMAGE_PNG).setImageDurationMs(1000).build()
            val edited = EditedMediaItem.Builder(media).setFrameRate(24)
                .setEffects(Effects(emptyList(), listOf(SensorEffect(calibration, settings)))).build()
            val transformer = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264)
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
                return requireNotNull(reader.getFrameAtTime(400_000, MediaMetadataRetriever.OPTION_CLOSEST))
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

