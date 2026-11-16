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

/** Published profile noise, with an optional independent map of stuck/dead pixels. */
@UnstableApi
class SensorEffect(private val calibration: NoiseCalibration, initial: LabSettings) : GlEffect {
    @Volatile var settings = initial
    override fun toGlShaderProgram(context: Context,useHdr: Boolean): GlShaderProgram {
        require(!useHdr) { "Questa edizione richiede un video SDR: nessuna conversione HDR automatica." }
        return try { SensorProgram(calibration,this) }
        catch(e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
    }
}

@UnstableApi
internal class SensorProgram(private val calibration: NoiseCalibration,private val effect: SensorEffect)
    : BaseGlShaderProgram(false,1) {
    private val program = GlProgram(VERTEX,FRAGMENT)
    private var width=1
    private var height=1
    private var jpeg: JpegCompression?=null
    init { program.setBufferAttribute("aFramePosition",GlUtil.getNormalizedCoordinateBounds(),4) }
    override fun configure(inputWidth: Int,inputHeight: Int): Size {
        width=inputWidth; height=inputHeight
        return Size(width,height)
    }
    override fun drawFrame(inputTexId: Int,presentationTimeUs: Long) {
        try {
            val s=effect.settings.sanitizedDefects()
            val timeMs=presentationTimeUs/1000L
            val compression=if(s.jpegEnabled && s.jpegAmount>0f && !s.original)
                (jpeg ?: JpegCompression().also { jpeg=it }) else null
            compression?.begin(width,height)
            try {
                program.use()
                setSensorUniforms(program,calibration,if(compression!=null) s.copy(compare=false) else s,inputTexId,width,height,timeMs,false,compression!=null)
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
                GlUtil.checkGlError()
                compression?.finish(inputTexId,s)
            } finally { compression?.restoreTarget() }
        } catch(e: Exception) { throw VideoFrameProcessingException(e,presentationTimeUs) }
    }
    override fun release() {
        try { super.release() } finally {
            try { try { program.delete() } finally { jpeg?.release() } } catch(e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
        }
    }
    companion object {
        internal const val VERTEX="""
            attribute vec4 aFramePosition;
            varying vec2 vUv;
            void main() { gl_Position=aFramePosition; vUv=aFramePosition.xy*0.5+0.5; }
        """
        internal const val FRAGMENT="""
            precision highp float;
            uniform sampler2D uTex;
            uniform vec2 uSize,uSeed,uTimeKey;
            uniform vec3 uShot,uRead;
            uniform float uOriginal,uSplit,uMedianRadius,uBitmap,uEncodeBitmap;
            uniform vec2 uPixel;
            uniform float uMode,uTime,uNoise,uExposure,uWarmth,uSaturation,uContrast;
            uniform float uDenoise,uSharpness,uVignette,uCapture,uReadFloor,uShadowGain,uHighlightProtect,uCoherence;
            uniform float uNaturalGrain,uGrainSize,uGrainLuma,uGrainChroma,uGrainSoftness,uGrainRate;
            uniform float uDefectCount,uDefectSize,uDefectIntensity,uDefectMode;
            uniform vec2 uDefectSeed;
            varying vec2 vUv;
            float random(vec2 p,float channel) {
                vec2 key=p+uSeed*vec2(17.17,31.31)+uTimeKey*vec2(0.173,0.317);
                vec3 h=fract(vec3(key,channel+uTimeKey.x*0.113+uTimeKey.y*0.197)*0.1031);
                h+=dot(h,h.yzx+33.33);
                return fract((h.x+h.y)*h.z);
            }
            float gaussian(vec2 p,float channel) {
                float a=max(random(p,channel),0.000001);
                float b=random(p.yx+vec2(41.0,17.0),channel+23.0);
                return sqrt(-2.0*log(a))*cos(6.28318530718*b);
            }
            float photonResidual(vec2 p,float channel,float lambda) {
                if(lambda<0.00001) return 0.0;
                if(lambda>=12.0) return gaussian(p,channel);
                float threshold=exp(-lambda);
                float product=1.0;
                for(int i=0;i<64;i++) {
                    product*=max(random(p+vec2(float(i)*7.13,float(i)*19.37),channel+float(i)*3.71),0.000001);
                    if(product<=threshold) return (float(i)-lambda)/sqrt(lambda);
                }
                return gaussian(p,channel);
            }
            float residual(vec2 p,float x,float a,float b,float channel) {
                a=max(a,0.0);
                float variance=max(a*x+b,0.0);
                if(variance<=0.0) return 0.0;
                if(a<=0.0 || x<=0.000000001) return sqrt(variance)*gaussian(p,channel+409.0);
                float shotVariance=a*x;
                float shot=photonResidual(p,channel,x/a);
                // Negative darktable b values are fitted intercepts: preserve TOTAL variance.
                if(b<=0.0) return sqrt(variance)*shot;
                return sqrt(shotVariance)*shot+sqrt(b)*gaussian(p,channel+409.0);
            }
            // No timestamp or noise seed: this map stays fixed across frames and takes.
            float defectRandom(vec2 p,float salt) {
                vec3 h=fract(vec3(p+uDefectSeed*vec2(17.17,31.31),salt)*0.1031);
                h+=dot(h,h.yzx+33.33);
                return fract((h.x+h.y)*h.z);
            }
            vec3 defectivePixel(vec2 p,vec3 value) {
                if(uDefectCount<0.5 || uDefectIntensity<=0.0) return value;
                // One possible defect per cell. A Feistel permutation gives each of the
                // 256 cells a unique rank, so increasing count retains existing points.
                vec2 cellSize=floor(uSize/16.0);
                if(min(cellSize.x,cellSize.y)<1.0) return value;
                vec2 cell=min(floor(p/cellSize),vec2(15.0));
                float left=cell.x;
                float right=cell.y;
                for(int i=0;i<4;i++) {
                    float next=mod(left+floor(defectRandom(vec2(right,float(i)),53.0)*16.0),16.0);
                    left=right; right=next;
                }
                if(left*16.0+right>=uDefectCount) return value;
                vec2 size=min(vec2(uDefectSize),cellSize);
                // Reserve room for the largest size so changing size keeps the anchor.
                vec2 origin=cell*cellSize+floor(vec2(defectRandom(cell,71.0),defectRandom(cell,137.0))*max(cellSize-5.0,vec2(1.0)));
                vec2 offset=p-origin;
                if(offset.x<0.0 || offset.y<0.0 || offset.x>=size.x || offset.y>=size.y) return value;
                float kind=uDefectMode;
                if(kind>2.5) kind=floor(defectRandom(cell,211.0)*3.0);
                vec3 stuck=vec3(1.0);
                if(kind>0.5 && kind<1.5) stuck=vec3(0.0);
                if(kind>1.5) {
                    float channel=floor(defectRandom(cell,307.0)*3.0);
                    stuck=channel<0.5 ? vec3(1.0,0.0,0.0) : (channel<1.5 ? vec3(0.0,1.0,0.0) : vec3(0.0,0.0,1.0));
                }
                return mix(value,stuck,uDefectIntensity);
            }
            vec4 textureInput(vec2 uv) {
                vec4 c=texture2D(uTex,uv);
                if(uBitmap>0.5 && c.a>0.0) c.rgb/=c.a;
                return c;
            }
            vec3 linearInput(vec2 uv) {
                vec3 c=textureInput(clamp(uv,0.5/uSize,1.0-0.5/uSize)).rgb;
                if(uBitmap<0.5) return c;
                return mix(c/12.92,pow((c+0.055)/1.055,vec3(2.4)),step(vec3(0.04045),c));
            }
            vec3 medianInput(vec2 uv) {
                if(uMedianRadius<0.5) return linearInput(uv);
                vec3 samples[25];
                int count=0;
                for(int y=-2;y<=2;y++) for(int x=-2;x<=2;x++) {
                    if(abs(float(x))<=uMedianRadius && abs(float(y))<=uMedianRadius) {
                        samples[count]=linearInput(uv+vec2(float(x),float(y))/uSize);
                        count++;
                    }
                }
                // Component-wise true median of the full 3x3 or 5x5 neighborhood.
                for(int pass=0;pass<25;pass++) for(int j=0;j<24;j++) {
                    if(j<count-1-pass) {
                        vec3 a=samples[j]; vec3 b=samples[j+1];
                        samples[j]=min(a,b); samples[j+1]=max(a,b);
                    }
                }
                return uMedianRadius<1.5 ? samples[4] : samples[12];
            }
            float legacyRandom(vec2 p, float seed) {
                vec3 h = fract(vec3(p + uSeed * vec2(17.17, 31.31), seed) * 0.1031);
                h += dot(h, h.yzx + 33.33);
                return fract((h.x + h.y) * h.z);
            }
            float legacyGaussian(vec2 p, float seed) {
                float a = max(legacyRandom(p, seed), 0.0001);
                float b = legacyRandom(p.yx + vec2(41.0,17.0), seed + 23.0);
                return sqrt(-2.0 * log(a)) * cos(6.28318530718 * b);
            }
            // Correlated Gaussian lattice: hard cells to smooth grains. Normalize the
            // interpolation weights so grain size does not silently change variance.
            float legacyGrainField(vec2 p, float seed) {
                vec2 cell = floor(p);
                vec2 f = fract(p);
                vec2 blend = mix(step(vec2(0.5), f), f*f*(3.0-2.0*f), uGrainSoftness);
                vec4 w = vec4((1.0-blend.x)*(1.0-blend.y), blend.x*(1.0-blend.y),
                              (1.0-blend.x)*blend.y, blend.x*blend.y);
                vec4 samples = vec4(legacyGaussian(cell, seed), legacyGaussian(cell+vec2(1.0,0.0), seed),
                                    legacyGaussian(cell+vec2(0.0,1.0), seed), legacyGaussian(cell+vec2(1.0,1.0), seed));
                return dot(w, samples) / max(sqrt(dot(w,w)), 0.001);
            }
            // Knuth Poisson sampling at low photon counts, normal approximation above 12.
            // Returns a standardized, zero-mean residual. No wall-clock dependence.
            float legacyPhotonResidual(vec2 p, float seed, float lambda) {
                if (lambda < 0.00001) return 0.0;
                if (lambda >= 12.0) return legacyGaussian(p, seed);
                float threshold = exp(-lambda);
                float product = 1.0;
                for (int i=0; i<64; i++) {
                    product *= max(legacyRandom(p + vec2(float(i)*7.13, float(i)*19.37), seed+float(i)*3.71), 0.000001);
                    if (product <= threshold) return (float(i)-lambda) / sqrt(lambda);
                }
                return legacyGaussian(p, seed);
            }
            float legacyCaptureSample(vec2 p, float seed, float lambda) {
                return lambda < 0.0 ? legacyGaussian(p, seed) : legacyPhotonResidual(p, seed, lambda);
            }
            float legacyCaptureField(vec2 p, float seed, float lambda) {
                vec2 cell = floor(p);
                vec2 f = fract(p);
                vec2 blend = mix(step(vec2(0.5), f), f*f*(3.0-2.0*f), uGrainSoftness);
                vec4 w = vec4((1.0-blend.x)*(1.0-blend.y), blend.x*(1.0-blend.y),
                              (1.0-blend.x)*blend.y, blend.x*blend.y);
                vec4 samples = vec4(legacyCaptureSample(cell,seed,lambda),legacyCaptureSample(cell+vec2(1.0,0.0),seed,lambda),
                                    legacyCaptureSample(cell+vec2(0.0,1.0),seed,lambda),legacyCaptureSample(cell+vec2(1.0,1.0),seed,lambda));
                return dot(w,samples) / max(sqrt(dot(w,w)), 0.001);
            }
            float legacyEvolvingField(vec2 p, float frame, float channel, float lambda) {
                float now = legacyCaptureField(p, frame*3.17+channel, lambda);
                if (uCoherence < 0.001) return now;
                float previous = legacyCaptureField(p, (frame-1.0)*3.17+channel, lambda);
                return (now + uCoherence*previous) / sqrt(1.0+uCoherence*uCoherence);
            }
            float legacyChannelNoise(vec2 p, float signal, float shot, float read, float frame, float channel) {
                float a = max(shot, 0.0);
                float shotVariance = a * signal;
                float result = 0.0;
                if (shotVariance > 0.000000001) {
                    result = sqrt(shotVariance) * legacyEvolvingField(p, frame, channel, signal/max(a,0.000000001));
                }
                float readVariance = max(read,0.0) + uReadFloor*uReadFloor;
                if (readVariance > 0.0000000001) {
                    result += sqrt(readVariance) * legacyEvolvingField(p, frame, channel+409.0, -1.0);
                }
                return result;
            }
            vec3 legacyCaptureNoise(vec2 uv, vec3 signal) {
                vec3 weights = vec3(0.2126,0.7152,0.0722);
                float y = dot(signal,weights);
                vec2 p = uv / uPixel / uGrainSize;
                float frame = floor(uTime * uGrainRate + 0.001);
                float mono = legacyChannelNoise(p,y,dot(uShot,weights),dot(uRead,weights),frame,0.0);
                vec3 chroma = vec3(0.0);
                if (uGrainChroma > 0.0) {
                    chroma = vec3(legacyChannelNoise(p*0.65,signal.r,uShot.r,uRead.r,frame,71.0),
                                  legacyChannelNoise(p*0.65,signal.g,uShot.g,uRead.g,frame,137.0),
                                  legacyChannelNoise(p*0.65,signal.b,uShot.b,uRead.b,frame,211.0));
                    chroma -= vec3(dot(chroma,weights));
                }
                float shadows = mix(uShadowGain,1.0,smoothstep(0.005,0.18,y));
                float highlights = 1.0-uHighlightProtect*smoothstep(0.55,0.98,y);
                return (vec3(mono)*uGrainLuma + chroma*uGrainChroma) * uNoise * shadows * highlights;
            }
            vec3 legacySensor(vec2 uv) {
                vec3 signal = max(medianInput(uv), 0.0);
                if (uNoise <= 0.0) return signal;
                if (uCapture > 0.5) return signal + legacyCaptureNoise(uv, signal);
                vec3 variance = max(uShot * signal + uRead, 0.0);
                if (uNaturalGrain > 0.5) {
                    vec2 p = uv / uPixel / uGrainSize;
                    float seed = floor(uTime * uGrainRate) * 0.137;
                    vec3 weights = vec3(0.2126, 0.7152, 0.0722);
                    float luma = legacyGrainField(p, seed) * sqrt(dot(variance, weights)) * uGrainLuma;
                    vec3 chroma = vec3(0.0);
                    if (uGrainChroma > 0.0) {
                        // Slightly broader colour grain; remove its luminance component.
                        chroma = vec3(legacyGrainField(p*0.65, seed+71.0), legacyGrainField(p*0.65, seed+137.0),
                                      legacyGrainField(p*0.65, seed+211.0)) * sqrt(variance);
                        chroma -= vec3(dot(chroma, weights));
                    }
                    return signal + (vec3(luma) + chroma*uGrainChroma) * uNoise;
                }
                vec2 pixel = floor(uv / uPixel);
                float frameSeed = floor(uTime * 1000.0) * 0.137;
                vec3 g = vec3(legacyGaussian(pixel, frameSeed), legacyGaussian(pixel, frameSeed + 71.0),
                              legacyGaussian(pixel, frameSeed + 137.0));
                // SIDD normalized RAW variance = beta1 * signal + beta2.
                return signal + g * sqrt(max(uShot * signal + uRead, 0.0)) * uNoise;
            }
            vec3 combinedSensor(vec2 uv) {
                if(uMode>0.5) return legacySensor(uv);
                vec3 x=max(medianInput(uv),0.0);
                vec2 p=floor(uv*uSize);
                vec3 noise=vec3(residual(p,x.r,uShot.r,uRead.r,71.0),residual(p,x.g,uShot.g,uRead.g,137.0),residual(p,x.b,uShot.b,uRead.b,211.0));
                return x+noise*uNoise;
            }
            vec3 displayOutput(vec3 c) {
                if(uEncodeBitmap<0.5) return c;
                return mix(c*12.92,1.055*pow(c,vec3(1.0/2.4))-0.055,step(vec3(0.0031308),c));
            }
            void main() {
                vec4 source=textureInput(vUv);
                if(uOriginal>0.5 || (uSplit>=0.0 && vUv.x<uSplit)) { gl_FragColor=source; return; }
                vec3 c = combinedSensor(vUv);
                vec3 avg = c;
                if (uDenoise > 0.0 || uSharpness > 0.0) {
                    avg = (combinedSensor(vUv + vec2(uPixel.x,0.0)) + combinedSensor(vUv - vec2(uPixel.x,0.0))
                         + combinedSensor(vUv + vec2(0.0,uPixel.y)) + combinedSensor(vUv - vec2(0.0,uPixel.y))) * 0.25;
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
                vec3 processed=clamp(c,0.0,1.0);
                vec2 p=floor(vUv*uSize);
                gl_FragColor=vec4(displayOutput(defectivePixel(p,processed)),source.a);
            }
        """
    }
}

@UnstableApi
internal fun setSensorUniforms(program: GlProgram, calibration: NoiseCalibration, s: LabSettings, inputTexId: Int, width: Int, height: Int, timeMs: Long, bitmap: Boolean, jpegPass: Boolean = false) {
    val n=calibration.at(s.iso,s.profileId)
    program.setSamplerTexIdUniform("uTex",inputTexId,0)
    program.setFloatsUniform("uSize",floatArrayOf(width.toFloat(),height.toFloat()))
    program.setFloatsUniform("uShot",n.shot)
    program.setFloatsUniform("uRead",n.read)
    program.setFloatsUniform("uSeed",floatArrayOf((s.noiseSeed%1024).toFloat(),(s.noiseSeed/1024).toFloat()))
    program.setFloatsUniform("uTimeKey",floatArrayOf((timeMs%65536L).toFloat(),((timeMs/65536L)%65536L).toFloat()))
    program.setFloatUniform("uOriginal",if(s.original) 1f else 0f)
    program.setFloatUniform("uSplit",if(s.compare) s.split else -1f)
    program.setFloatUniform("uDefectCount",if(s.defectsEnabled) s.defectCount.toFloat() else 0f)
    program.setFloatUniform("uDefectSize",s.defectSize.toFloat())
    program.setFloatUniform("uDefectIntensity",s.defectIntensity)
    program.setFloatUniform("uDefectMode",s.defectMode.toFloat())
    program.setFloatsUniform("uDefectSeed",floatArrayOf((s.defectSeed%1024).toFloat(),(s.defectSeed/1024).toFloat()))
    program.setFloatUniform("uMedianRadius",s.medianRadius.toFloat())
    program.setFloatUniform("uBitmap",if(bitmap) 1f else 0f)
    program.setFloatUniform("uEncodeBitmap",if(bitmap && !jpegPass) 1f else 0f)
    program.setFloatsUniform("uPixel",floatArrayOf(1f/width,1f/height))
    program.setFloatUniform("uMode",s.noiseMode.toFloat())
    program.setFloatUniform("uTime",timeMs/1000f)
    program.setFloatUniform("uCapture",if(s.noiseMode==3) 1f else 0f)
    program.setFloatUniform("uNaturalGrain",if(s.noiseMode>=2) 1f else 0f)
    program.setFloatUniform("uReadFloor",s.readNoise)
    program.setFloatUniform("uCoherence",s.temporalCoherence)
    program.setFloatUniform("uGrainSize",maxOf(1f,s.grainSize*height/1080f))
    program.setFloatUniform("uNoise",s.noise)
    program.setFloatUniform("uShadowGain",s.shadowGain)
    program.setFloatUniform("uHighlightProtect",s.highlightProtect)
    program.setFloatUniform("uGrainLuma",s.grainLuma)
    program.setFloatUniform("uGrainChroma",s.grainChroma)
    program.setFloatUniform("uGrainSoftness",s.grainSoftness)
    program.setFloatUniform("uGrainRate",s.grainRate)
    program.setFloatUniform("uExposure",s.exposure)
    program.setFloatUniform("uWarmth",s.warmth)
    program.setFloatUniform("uSaturation",s.saturation)
    program.setFloatUniform("uContrast",s.contrast)
    program.setFloatUniform("uDenoise",s.denoise)
    program.setFloatUniform("uSharpness",s.sharpness)
    program.setFloatUniform("uVignette",s.vignette)

}
