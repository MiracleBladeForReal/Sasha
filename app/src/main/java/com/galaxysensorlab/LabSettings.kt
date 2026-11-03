package com.galaxysensorlab

import android.content.Context
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.sqrt

data class LabSettings(
    val profileId: String = "sidd:s6",
    val iso: Float = 800f,
    val noise: Float = 0.7f,
    val captureMode: Boolean = true,
    val noiseSeed: Int = 0,
    val readNoise: Float = 0.001f,
    val shadowGain: Float = 1.2f,
    val highlightProtect: Float = 0.5f,
    val temporalCoherence: Float = 0.35f,
    val naturalGrain: Boolean = true,
    val grainSize: Float = 2f,
    val grainLuma: Float = 1f,
    val grainChroma: Float = 0.15f,
    val grainSoftness: Float = 0.85f,
    val grainRate: Float = 24f,
    val exposure: Float = 0f,
    val warmth: Float = 0f,
    val saturation: Float = 1f,
    val contrast: Float = 1f,
    val denoise: Float = 0f,
    val sharpness: Float = 0f,
    val vignette: Float = 0f,
    val compare: Boolean = false,
    val original: Boolean = false,
    val split: Float = 0.5f
) {
    fun toJson(): String = JSONObject().apply {
        put("captureMode", captureMode); put("noiseSeed", noiseSeed)
        put("readNoise", readNoise); put("shadowGain", shadowGain)
        put("highlightProtect", highlightProtect); put("temporalCoherence", temporalCoherence)
        put("profileId", profileId); put("iso", iso); put("noise", noise); put("exposure", exposure)
        put("naturalGrain", naturalGrain); put("grainSize", grainSize)
        put("grainLuma", grainLuma); put("grainChroma", grainChroma)
        put("grainSoftness", grainSoftness); put("grainRate", grainRate)
        put("warmth", warmth); put("saturation", saturation); put("contrast", contrast)
        put("denoise", denoise); put("sharpness", sharpness); put("vignette", vignette)
    }.toString()

    companion object {
        fun fromJson(text: String): LabSettings {
            val j = JSONObject(text)
            return LabSettings(
                profileId = j.optString("profileId", "sidd:s6"),
                captureMode = j.optBoolean("captureMode", true),
                noiseSeed = j.optInt("noiseSeed", 0).coerceIn(0, 1_048_575),
                readNoise = j.optDouble("readNoise", 0.001).toFloat().coerceIn(0f, 0.02f),
                shadowGain = j.optDouble("shadowGain", 1.2).toFloat().coerceIn(0f, 3f),
                highlightProtect = j.optDouble("highlightProtect", 0.5).toFloat().coerceIn(0f, 1f),
                temporalCoherence = j.optDouble("temporalCoherence", 0.35).toFloat().coerceIn(0f, 1f),
                iso = j.optDouble("iso", 800.0).toFloat().coerceIn(1f, 10_000_000f),
                noise = j.optDouble("noise", 0.7).toFloat().coerceIn(0f, 2f),
                naturalGrain = j.optBoolean("naturalGrain", true),
                grainSize = j.optDouble("grainSize", 2.0).toFloat().coerceIn(0.5f, 12f),
                grainLuma = j.optDouble("grainLuma", 1.0).toFloat().coerceIn(0f, 1.5f),
                grainChroma = j.optDouble("grainChroma", 0.15).toFloat().coerceIn(0f, 1.5f),
                grainSoftness = j.optDouble("grainSoftness", 0.85).toFloat().coerceIn(0f, 1f),
                grainRate = j.optDouble("grainRate", 24.0).toFloat().coerceIn(1f, 60f),
                exposure = j.optDouble("exposure", 0.0).toFloat().coerceIn(-3f, 3f),
                warmth = j.optDouble("warmth", 0.0).toFloat().coerceIn(-1f, 1f),
                saturation = j.optDouble("saturation", 1.0).toFloat().coerceIn(0f, 2f),
                contrast = j.optDouble("contrast", 1.0).toFloat().coerceIn(0.5f, 1.5f),
                denoise = j.optDouble("denoise", 0.0).toFloat().coerceIn(0f, 1f),
                sharpness = j.optDouble("sharpness", 0.0).toFloat().coerceIn(0f, 1f),
                vignette = j.optDouble("vignette", 0.0).toFloat().coerceIn(0f, 1f)
            )
        }
    }
}

data class NoisePoint(val iso: Float, val shot: FloatArray, val read: FloatArray)

data class CameraNoiseProfile(
    val id: String,
    val maker: String,
    val model: String,
    val dataset: String,
    val source: String,
    val license: String,
    val attribution: String,
    val points: List<NoisePoint>,
    val logInterpolation: Boolean
) {
    val displayName: String get() = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
    val minIso: Float get() = points.first().iso
    val maxIso: Float get() = points.last().iso
}

/** Profile coefficients are normalized linear-RAW variance parameters, not standard deviations. */
class NoiseCalibration(context: Context) {
    companion object { const val DEFAULT_PROFILE = "sidd:s6" }
    val cameras: List<CameraNoiseProfile>
    private val byId: Map<String, CameraNoiseProfile>
    // Retain the original S6 API and coefficients for regression checks.
    val source: String get() = profile(DEFAULT_PROFILE).source
    val points: List<NoisePoint> get() = profile(DEFAULT_PROFILE).points

    init {
        val j = JSONObject(context.assets.open("sidd_s6.json").bufferedReader().use { it.readText() })
        val a = j.getJSONArray("profiles")
        val s6points = (0 until a.length()).map { i ->
            val p = a.getJSONObject(i)
            NoisePoint(p.getDouble("iso").toFloat(),
                FloatArray(3) { p.getJSONArray("shot").getDouble(it).toFloat() },
                FloatArray(3) { p.getJSONArray("read").getDouble(it).toFloat() })
        }.sortedBy { it.iso }
        val s6 = CameraNoiseProfile(DEFAULT_PROFILE, "Samsung", "Galaxy S6 Edge", "SIDD",
            j.getString("source"), "MIT", "Abdelhamed, Lin, Brown · CVPR 2018", s6points, true)
        val dt = JSONObject(context.assets.open("darktable-noiseprofiles.json").bufferedReader().use { it.readText() })
        val makers = dt.getJSONArray("noiseprofiles")
        val catalog = mutableListOf<CameraNoiseProfile>()
        for (i in 0 until makers.length()) {
            val maker = makers.getJSONObject(i)
            val makerName = maker.getString("maker")
            val models = maker.getJSONArray("models")
            for (k in 0 until models.length()) {
                val camera = models.getJSONObject(k)
                val model = camera.getString("model")
                val measurements = camera.getJSONArray("profiles")
                val points = (0 until measurements.length()).map { index ->
                    val point = measurements.getJSONObject(index)
                    NoisePoint(point.getDouble("iso").toFloat(),
                        FloatArray(3) { point.getJSONArray("a").getDouble(it).toFloat() },
                        FloatArray(3) { point.getJSONArray("b").getDouble(it).toFloat() })
                }.filter { it.iso > 0 }.distinctBy { it.iso }.sortedBy { it.iso }
                if (points.isNotEmpty()) catalog.add(CameraNoiseProfile(
                    "darktable:$makerName:$model", makerName, model, "darktable",
                    "https://github.com/darktable-org/darktable/blob/879067695ea85f42d238a5c1603a021d14d71d3f/data/noiseprofiles.json",
                    "GPL-3.0-or-later", camera.optString("comment", "darktable contributors"), points, false))
            }
        }
        cameras = listOf(s6) + catalog.sortedWith(compareBy({ it.maker.lowercase() }, { it.model.lowercase() }))
        byId = cameras.associateBy { it.id }
        check(byId.size == cameras.size) { "Duplicate camera identifiers" }
    }

    fun profile(id: String): CameraNoiseProfile = byId[id] ?: byId.getValue(DEFAULT_PROFILE)

    fun search(query: String): List<CameraNoiseProfile> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return cameras.filter { camera ->
            val text = "${camera.displayName} ${camera.dataset}".lowercase()
            words.all { text.contains(it) }
        }
    }

    fun at(iso: Float, profileId: String = DEFAULT_PROFILE): NoisePoint {
        val camera = profile(profileId)
        val points = camera.points
        val clamped = iso.coerceIn(camera.minIso, camera.maxIso)
        val hi = points.firstOrNull { it.iso >= clamped } ?: points.last()
        val lo = points.lastOrNull { it.iso <= clamped } ?: points.first()
        if (lo.iso == hi.iso) return lo
        // SIDD retains its previous log-ISO interpolation. Darktable uses linear ISO.
        val t = if (camera.logInterpolation) ln(clamped / lo.iso) / ln(hi.iso / lo.iso)
                else (clamped-lo.iso)/(hi.iso-lo.iso)
        return NoisePoint(clamped,
            FloatArray(3) { lo.shot[it] + (hi.shot[it] - lo.shot[it]) * t },
            FloatArray(3) { lo.read[it] + (hi.read[it] - lo.read[it]) * t })
    }

    fun sigma(iso: Float, signal: Float, profileId: String = DEFAULT_PROFILE): Float {
        val p = at(iso, profileId)
        // Negative fitted offsets are valid in the source data. Clamp the final variance only.
        return sqrt((p.shot[1] * signal.coerceAtLeast(0f) + p.read[1]).coerceAtLeast(0f))
    }
}

/** A fresh OS-seeded realization; reproducible shader sampling keeps preview and export consistent. */
object NoiseSeeds {
    private val random = java.security.SecureRandom()
    fun next(previous: Int = -1): Int {
        var next: Int
        do { next = random.nextInt(1 shl 20) } while (next == previous)
        return next
    }
}
