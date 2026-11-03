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

/** Signal-dependent profile noise only. No creative processing or added read floor. */
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
private class SensorProgram(private val calibration: NoiseCalibration,private val effect: SensorEffect)
    : BaseGlShaderProgram(false,1) {
    private val program = GlProgram(VERTEX,FRAGMENT)
    private var width=1
    private var height=1
    init { program.setBufferAttribute("aFramePosition",GlUtil.getNormalizedCoordinateBounds(),4) }
    override fun configure(inputWidth: Int,inputHeight: Int): Size {
        width=inputWidth; height=inputHeight
        return Size(width,height)
    }
    override fun drawFrame(inputTexId: Int,presentationTimeUs: Long) {
        try {
            val s=effect.settings
            val n=calibration.at(s.iso,s.profileId)
            val timeMs=presentationTimeUs/1000L
            program.use()
            program.setSamplerTexIdUniform("uTex",inputTexId,0)
            program.setFloatsUniform("uSize",floatArrayOf(width.toFloat(),height.toFloat()))
            program.setFloatsUniform("uShot",n.shot)
            program.setFloatsUniform("uRead",n.read)
            program.setFloatsUniform("uSeed",floatArrayOf((s.noiseSeed%1024).toFloat(),(s.noiseSeed/1024).toFloat()))
            program.setFloatsUniform("uTimeKey",floatArrayOf((timeMs%65536L).toFloat(),((timeMs/65536L)%65536L).toFloat()))
            program.setFloatUniform("uOriginal",if(s.original) 1f else 0f)
            program.setFloatUniform("uSplit",if(s.compare) s.split else -1f)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
            GlUtil.checkGlError()
        } catch(e: GlUtil.GlException) { throw VideoFrameProcessingException(e,presentationTimeUs) }
    }
    override fun release() {
        try { super.release() } finally {
            try { program.delete() } catch(e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
        }
    }
    companion object {
        private const val VERTEX="""
            attribute vec4 aFramePosition;
            varying vec2 vUv;
            void main() { gl_Position=aFramePosition; vUv=aFramePosition.xy*0.5+0.5; }
        """
        private const val FRAGMENT="""
            precision highp float;
            uniform sampler2D uTex;
            uniform vec2 uSize,uSeed,uTimeKey;
            uniform vec3 uShot,uRead;
            uniform float uOriginal,uSplit;
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
            void main() {
                vec4 source=texture2D(uTex,vUv);
                if(uOriginal>0.5 || (uSplit>=0.0 && vUv.x<uSplit)) { gl_FragColor=source; return; }
                vec3 x=max(source.rgb,0.0);
                vec2 p=floor(vUv*uSize);
                vec3 noise=vec3(residual(p,x.r,uShot.r,uRead.r,71.0),
                                residual(p,x.g,uShot.g,uRead.g,137.0),
                                residual(p,x.b,uShot.b,uRead.b,211.0));
                // Output clipping is the only nonlinearity; exposure/color remain untouched.
                gl_FragColor=vec4(clamp(x+noise,0.0,1.0),source.a);
            }
        """
    }
}
