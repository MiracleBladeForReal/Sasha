package com.galaxysensorlab

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
        assertTrue("Exposure must change actual encoded pixels", meanDifference(baseline, brighter) > 12)
        assertTrue("SIDD noise must change actual encoded pixels", meanDifference(baseline, noisy) > 2)
        assertTrue("Original bypass must preserve pixels", meanDifference(baseline, bypass) < 3)
        listOf(baseline, brighter, noisy, bypass).forEach { it.recycle() }
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

    private fun render(input: File, settings: LabSettings): Bitmap {
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
