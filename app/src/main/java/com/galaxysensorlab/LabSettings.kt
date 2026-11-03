package com.galaxysensorlab

import android.content.Context
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.sqrt

data class LabSettings(
    val iso: Float = 800f,
    val noise: Float = 0.7f,
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
        put("iso", iso); put("noise", noise); put("exposure", exposure)
        put("warmth", warmth); put("saturation", saturation); put("contrast", contrast)
        put("denoise", denoise); put("sharpness", sharpness); put("vignette", vignette)
    }.toString()

    companion object {
        fun fromJson(text: String): LabSettings {
            val j = JSONObject(text)
            return LabSettings(
                iso = j.optDouble("iso", 800.0).toFloat().coerceIn(100f, 3200f),
                noise = j.optDouble("noise", 0.7).toFloat().coerceIn(0f, 2f),
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

class NoiseCalibration(context: Context) {
    val source: String
    val points: List<NoisePoint>
    init {
        val j = JSONObject(context.assets.open("sidd_s6.json").bufferedReader().use { it.readText() })
        source = j.getString("source")
        val a = j.getJSONArray("profiles")
        points = (0 until a.length()).map { i ->
            val p = a.getJSONObject(i)
            NoisePoint(p.getDouble("iso").toFloat(),
                FloatArray(3) { p.getJSONArray("shot").getDouble(it).toFloat() },
                FloatArray(3) { p.getJSONArray("read").getDouble(it).toFloat() })
        }.sortedBy { it.iso }
    }

    fun at(iso: Float): NoisePoint {
        val clamped = iso.coerceIn(points.first().iso, points.last().iso)
        val hi = points.firstOrNull { it.iso >= clamped } ?: points.last()
        val lo = points.lastOrNull { it.iso <= clamped } ?: points.first()
        if (lo.iso == hi.iso) return lo
        // Interpolate coefficients in log ISO; never extrapolate outside the published range.
        val t = ln(clamped / lo.iso) / ln(hi.iso / lo.iso)
        return NoisePoint(clamped,
            FloatArray(3) { lo.shot[it] + (hi.shot[it] - lo.shot[it]) * t },
            FloatArray(3) { lo.read[it] + (hi.read[it] - lo.read[it]) * t })
    }

    fun sigma(iso: Float, signal: Float): Float {
        val p = at(iso)
        return sqrt(p.shot[1] * signal.coerceAtLeast(0f) + p.read[1])
    }
}
