package com.galaxysensorlab

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/** Same GPU effect for preview and export. Settings are read atomically once per frame. */
@UnstableApi
class SensorEffect(private val calibration: NoiseCalibration, initial: LabSettings) : GlEffect {
    @Volatile var settings = initial

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        try { SensorProgram(calibration, this, useHdr) }
        catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
}

@UnstableApi
private class SensorProgram(
    private val calibration: NoiseCalibration,
    private val effect: SensorEffect,
    useHdr: Boolean
) : BaseGlShaderProgram(useHdr, 1) {
    private val program = GlProgram(VERTEX, FRAGMENT)
    private var width = 1
    private var height = 1
    init { program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4) }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth; height = inputHeight
        return Size(width, height)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val s = effect.settings
            val n = calibration.at(s.iso, s.profileId)
            program.use()
            program.setSamplerTexIdUniform("uTex", inputTexId, 0)
            program.setFloatsUniform("uPixel", floatArrayOf(1f / width, 1f / height))
            program.setFloatsUniform("uShot", n.shot)
            program.setFloatsUniform("uRead", n.read)
            program.setFloatUniform("uTime", (presentationTimeUs % 3_600_000_000L) / 1_000_000f)
            program.setFloatUniform("uNoise", s.noise)
            program.setFloatsUniform("uSeed", floatArrayOf((s.noiseSeed % 1024).toFloat(), (s.noiseSeed / 1024).toFloat()))
            program.setFloatUniform("uCapture", if (s.captureMode && s.naturalGrain) 1f else 0f)
            program.setFloatUniform("uReadFloor", s.readNoise)
            program.setFloatUniform("uShadowGain", s.shadowGain)
            program.setFloatUniform("uHighlightProtect", s.highlightProtect)
            program.setFloatUniform("uCoherence", s.temporalCoherence)
            program.setFloatUniform("uNaturalGrain", if (s.naturalGrain) 1f else 0f)
            // Reference-pixel size keeps the look consistent after the export resize.
            program.setFloatUniform("uGrainSize", maxOf(1f, s.grainSize * height / 1080f))
            program.setFloatUniform("uGrainLuma", s.grainLuma)
            program.setFloatUniform("uGrainChroma", s.grainChroma)
            program.setFloatUniform("uGrainSoftness", s.grainSoftness)
            program.setFloatUniform("uGrainRate", s.grainRate)
            program.setFloatUniform("uExposure", s.exposure)
            program.setFloatUniform("uWarmth", s.warmth)
            program.setFloatUniform("uSaturation", s.saturation)
            program.setFloatUniform("uContrast", s.contrast)
            program.setFloatUniform("uDenoise", s.denoise)
            program.setFloatUniform("uSharpness", s.sharpness)
            program.setFloatUniform("uVignette", s.vignette)
            program.setFloatUniform("uOriginal", if (s.original) 1f else 0f)
            program.setFloatUniform("uSplit", if (s.compare) s.split else -1f)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e, presentationTimeUs) }
    }

    override fun release() {
        try { super.release() } finally {
            try { program.delete() }
            catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
        }
    }

    companion object {
        private const val VERTEX = """
            attribute vec4 aFramePosition;
            varying vec2 vUv;
            void main() {
                gl_Position = aFramePosition;
                vUv = aFramePosition.xy * 0.5 + 0.5;
            }
        """
        private const val FRAGMENT = """
            precision highp float;
            uniform sampler2D uTex;
            uniform vec2 uPixel;
            uniform vec2 uSeed;
            uniform float uCapture, uReadFloor, uShadowGain, uHighlightProtect, uCoherence;
            uniform vec3 uShot;
            uniform vec3 uRead;
            uniform float uTime, uNoise, uExposure, uWarmth, uSaturation, uContrast;
            uniform float uDenoise, uSharpness, uVignette, uOriginal, uSplit;
            uniform float uNaturalGrain, uGrainSize, uGrainLuma, uGrainChroma;
            uniform float uGrainSoftness, uGrainRate;
            varying vec2 vUv;
            float random(vec2 p, float seed) {
                vec3 h = fract(vec3(p + uSeed * vec2(17.17, 31.31), seed) * 0.1031);
                h += dot(h, h.yzx + 33.33);
                return fract((h.x + h.y) * h.z);
            }
            float gaussian(vec2 p, float seed) {
                float a = max(random(p, seed), 0.0001);
                float b = random(p.yx + vec2(41.0,17.0), seed + 23.0);
                return sqrt(-2.0 * log(a)) * cos(6.28318530718 * b);
            }
            // Correlated Gaussian lattice: hard cells to smooth grains. Normalize the
            // interpolation weights so grain size does not silently change variance.
            float grainField(vec2 p, float seed) {
                vec2 cell = floor(p);
                vec2 f = fract(p);
                vec2 blend = mix(step(vec2(0.5), f), f*f*(3.0-2.0*f), uGrainSoftness);
                vec4 w = vec4((1.0-blend.x)*(1.0-blend.y), blend.x*(1.0-blend.y),
                              (1.0-blend.x)*blend.y, blend.x*blend.y);
                vec4 samples = vec4(gaussian(cell, seed), gaussian(cell+vec2(1.0,0.0), seed),
                                    gaussian(cell+vec2(0.0,1.0), seed), gaussian(cell+vec2(1.0,1.0), seed));
                return dot(w, samples) / max(sqrt(dot(w,w)), 0.001);
            }
            // Knuth Poisson sampling at low photon counts, normal approximation above 12.
            // Returns a standardized, zero-mean residual. No wall-clock dependence.
            float photonResidual(vec2 p, float seed, float lambda) {
                if (lambda < 0.00001) return 0.0;
                if (lambda >= 12.0) return gaussian(p, seed);
                float threshold = exp(-lambda);
                float product = 1.0;
                for (int i=0; i<64; i++) {
                    product *= max(random(p + vec2(float(i)*7.13, float(i)*19.37), seed+float(i)*3.71), 0.000001);
                    if (product <= threshold) return (float(i)-lambda) / sqrt(lambda);
                }
                return gaussian(p, seed);
            }
            float captureSample(vec2 p, float seed, float lambda) {
                return lambda < 0.0 ? gaussian(p, seed) : photonResidual(p, seed, lambda);
            }
            float captureField(vec2 p, float seed, float lambda) {
                vec2 cell = floor(p);
                vec2 f = fract(p);
                vec2 blend = mix(step(vec2(0.5), f), f*f*(3.0-2.0*f), uGrainSoftness);
                vec4 w = vec4((1.0-blend.x)*(1.0-blend.y), blend.x*(1.0-blend.y),
                              (1.0-blend.x)*blend.y, blend.x*blend.y);
                vec4 samples = vec4(captureSample(cell,seed,lambda),captureSample(cell+vec2(1.0,0.0),seed,lambda),
                                    captureSample(cell+vec2(0.0,1.0),seed,lambda),captureSample(cell+vec2(1.0,1.0),seed,lambda));
                return dot(w,samples) / max(sqrt(dot(w,w)), 0.001);
            }
            float evolvingField(vec2 p, float frame, float channel, float lambda) {
                float now = captureField(p, frame*3.17+channel, lambda);
                if (uCoherence < 0.001) return now;
                float previous = captureField(p, (frame-1.0)*3.17+channel, lambda);
                return (now + uCoherence*previous) / sqrt(1.0+uCoherence*uCoherence);
            }
            float channelNoise(vec2 p, float signal, float shot, float read, float frame, float channel) {
                float a = max(shot, 0.0);
                float shotVariance = a * signal;
                float result = 0.0;
                if (shotVariance > 0.000000001) {
                    result = sqrt(shotVariance) * evolvingField(p, frame, channel, signal/max(a,0.000000001));
                }
                float readVariance = max(read,0.0) + uReadFloor*uReadFloor;
                if (readVariance > 0.0000000001) {
                    result += sqrt(readVariance) * evolvingField(p, frame, channel+409.0, -1.0);
                }
                return result;
            }
            vec3 captureNoise(vec2 uv, vec3 signal) {
                vec3 weights = vec3(0.2126,0.7152,0.0722);
                float y = dot(signal,weights);
                vec2 p = uv / uPixel / uGrainSize;
                float frame = floor(uTime * uGrainRate + 0.001);
                float mono = channelNoise(p,y,dot(uShot,weights),dot(uRead,weights),frame,0.0);
                vec3 chroma = vec3(0.0);
                if (uGrainChroma > 0.0) {
                    chroma = vec3(channelNoise(p*0.65,signal.r,uShot.r,uRead.r,frame,71.0),
                                  channelNoise(p*0.65,signal.g,uShot.g,uRead.g,frame,137.0),
                                  channelNoise(p*0.65,signal.b,uShot.b,uRead.b,frame,211.0));
                    chroma -= vec3(dot(chroma,weights));
                }
                float shadows = mix(uShadowGain,1.0,smoothstep(0.005,0.18,y));
                float highlights = 1.0-uHighlightProtect*smoothstep(0.55,0.98,y);
                return (vec3(mono)*uGrainLuma + chroma*uGrainChroma) * uNoise * shadows * highlights;
            }
            vec3 sensor(vec2 uv) {
                vec3 signal = max(texture2D(uTex, clamp(uv, 0.0, 1.0)).rgb, 0.0);
                if (uNoise <= 0.0) return signal;
                if (uCapture > 0.5) return signal + captureNoise(uv, signal);
                vec3 variance = max(uShot * signal + uRead, 0.0);
                if (uNaturalGrain > 0.5) {
                    vec2 p = uv / uPixel / uGrainSize;
                    float seed = floor(uTime * uGrainRate) * 0.137;
                    vec3 weights = vec3(0.2126, 0.7152, 0.0722);
                    float luma = grainField(p, seed) * sqrt(dot(variance, weights)) * uGrainLuma;
                    vec3 chroma = vec3(0.0);
                    if (uGrainChroma > 0.0) {
                        // Slightly broader colour grain; remove its luminance component.
                        chroma = vec3(grainField(p*0.65, seed+71.0), grainField(p*0.65, seed+137.0),
                                      grainField(p*0.65, seed+211.0)) * sqrt(variance);
                        chroma -= vec3(dot(chroma, weights));
                    }
                    return signal + (vec3(luma) + chroma*uGrainChroma) * uNoise;
                }
                vec2 pixel = floor(uv / uPixel);
                float frameSeed = floor(uTime * 1000.0) * 0.137;
                vec3 g = vec3(gaussian(pixel, frameSeed), gaussian(pixel, frameSeed + 71.0),
                              gaussian(pixel, frameSeed + 137.0));
                // SIDD normalized RAW variance = beta1 * signal + beta2.
                return signal + g * sqrt(max(uShot * signal + uRead, 0.0)) * uNoise;
            }
            void main() {
                vec4 original = texture2D(uTex, vUv);
                if (uOriginal > 0.5 || (uSplit >= 0.0 && vUv.x < uSplit)) {
                    gl_FragColor = original;
                    return;
                }
                vec3 c = sensor(vUv);
                vec3 avg = c;
                if (uDenoise > 0.0 || uSharpness > 0.0) {
                    avg = (sensor(vUv + vec2(uPixel.x,0.0)) + sensor(vUv - vec2(uPixel.x,0.0))
                         + sensor(vUv + vec2(0.0,uPixel.y)) + sensor(vUv - vec2(0.0,uPixel.y))) * 0.25;
                }
                float expectedSigma = sqrt(max(dot(uShot * max(c, 0.0) + uRead, vec3(0.333333)), 0.0)) * uNoise;
                float edge = smoothstep(0.015 + expectedSigma, 0.12 + 2.0 * expectedSigma, length(c - avg));
                c = mix(c, avg, uDenoise * (1.0 - edge) * 0.85);
                c += (c - avg) * uSharpness * 0.7;
                c *= exp2(uExposure);
                c *= vec3(1.0 + 0.25*uWarmth, 1.0, 1.0 - 0.25*uWarmth);
                float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
                c = mix(vec3(luma), c, uSaturation);
                c = (c - 0.18) * uContrast + 0.18;
                float radius = length((vUv - 0.5) * 1.41421356);
                c *= 1.0 - uVignette * smoothstep(0.25, 0.75, radius) * 0.8;
                if (uSplit >= 0.0 && abs(vUv.x - uSplit) < uPixel.x * 1.5) c = vec3(0.1, 0.8, 0.7);
                gl_FragColor = vec4(clamp(c, 0.0, 1.0), original.a);
            }
        """
    }
}
