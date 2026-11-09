package com.galaxysensorlab

import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * JPEG-style 8x8 DCT, JPEG quantization tables and inverse DCT, after sensor development.
 * Four separable GPU passes: 8 samples per transform axis, no CPU frame readback.
 * Signed coefficients use two RGB byte banks (16 bits/channel) in RGBA8 textures so
 * the effect does not require floating-point framebuffer extensions on GLES 2 devices.
 * This simulates the visible loss, not a JPEG bitstream or inter-frame video codec.
 */
@UnstableApi
internal class JpegCompression {
    private val programs = (0..3).map { stage ->
        GlProgram(VERTEX, "#define STAGE $stage\n" + FRAGMENT).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
        }
    }
    private var textures = IntArray(3)
    private var fbos = IntArray(3)
    private var quantTexture = 0
    private var width = 0
    private var height = 0
    private var paddedWidth = 0
    private var paddedHeight = 0
    private var horizontalBanks = true
    private var targetFbo = 0
    private val targetViewport = IntArray(4)
    private var dither = false
    private var blend = false

    /** Save the caller's target and redirect the existing sensor shader into our input. */
    fun begin(inputWidth: Int, inputHeight: Int) {
        val binding = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, binding, 0)
        targetFbo = binding[0]
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, targetViewport, 0)
        dither = GLES20.glIsEnabled(GLES20.GL_DITHER)
        blend = GLES20.glIsEnabled(GLES20.GL_BLEND)
        try {
            if (width != inputWidth || height != inputHeight) allocate(inputWidth, inputHeight)
            GLES20.glDisable(GLES20.GL_DITHER)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[0])
            GLES20.glViewport(0, 0, width, height)
        } catch (e: Exception) {
            restoreTarget()
            throw e
        }
    }

    fun finish(originalTexId: Int, settings: LabSettings) {
        try {
            val packedWidth = paddedWidth * if (horizontalBanks) 2 else 1
            val packedHeight = paddedHeight * if (horizontalBanks) 1 else 2
            val amount = settings.jpegAmount.coerceIn(0f, 1f)
            val quality = 100f - 95f * amount
            val scale = if (quality < 50f) 5000f / quality else 200f - 2f * quality
            for (stage in 0..3) {
                if (stage == 3) {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFbo)
                    GLES20.glViewport(targetViewport[0], targetViewport[1], targetViewport[2], targetViewport[3])
                } else {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[if (stage == 1) 2 else 1])
                    GLES20.glViewport(0, 0, packedWidth, packedHeight)
                }
                val program = programs[stage]
                program.use()
                val input = when (stage) { 0 -> textures[0]; 2 -> textures[2]; else -> textures[1] }
                program.setSamplerTexIdUniform("uTex", input, 0)
                program.setFloatsUniform("uPadded", floatArrayOf(paddedWidth.toFloat(), paddedHeight.toFloat()))
                program.setFloatUniform("uHorizontalBanks", if (horizontalBanks) 1f else 0f)
                if (stage == 0 || stage == 3) {
                    program.setFloatsUniform("uSize", floatArrayOf(width.toFloat(), height.toFloat()))
                }
                if (stage == 1) {
                    program.setSamplerTexIdUniform("uQuant", quantTexture, 1)
                    program.setFloatUniform("uScale", scale)
                }
                if (stage == 3) {
                    program.setSamplerTexIdUniform("uOriginal", originalTexId, 1)
                    program.setSamplerTexIdUniform("uDeveloped", textures[0], 2)
                    // A short blend-in avoids a visible jump at the zero end of the slider.
                    program.setFloatUniform("uMix", (amount * 10f).coerceAtMost(1f))
                    program.setFloatUniform("uSplit", if (settings.compare) settings.split else -1f)
                }
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            }
        } finally { restoreTarget() }
    }

    fun restoreTarget() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFbo)
        GLES20.glViewport(targetViewport[0], targetViewport[1], targetViewport[2], targetViewport[3])
        if (dither) GLES20.glEnable(GLES20.GL_DITHER) else GLES20.glDisable(GLES20.GL_DITHER)
        if (blend) GLES20.glEnable(GLES20.GL_BLEND) else GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun allocate(w: Int, h: Int) {
        deleteTargets()
        paddedWidth = (w + 7) / 8 * 8
        paddedHeight = (h + 7) / 8 * 8
        val maxSize = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0)
        horizontalBanks = paddedWidth * 2 <= maxSize[0]
        check(paddedWidth <= maxSize[0] && paddedHeight <= maxSize[0] &&
            (horizontalBanks || paddedHeight * 2 <= maxSize[0])) {
            "Video troppo grande per l'effetto JPEG su questa GPU. Prova una sorgente più piccola."
        }
        try {
            GLES20.glGenTextures(3, textures, 0)
            GLES20.glGenFramebuffers(3, fbos, 0)
            for (i in 0..2) {
                val tw = if (i == 0) w else paddedWidth * if (horizontalBanks) 2 else 1
                val th = if (i == 0) h else paddedHeight * if (horizontalBanks) 1 else 2
                setupTexture(textures[i], tw, th, null)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[i])
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, textures[i], 0)
                check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    "Impossibile creare il buffer GPU per l'effetto JPEG."
                }
            }
            if (quantTexture == 0) {
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                quantTexture = ids[0]
                val bytes = ByteBuffer.allocateDirect(8 * 8 * 4)
                for (i in 0..63) {
                    bytes.put(LUMA[i].toByte()).put(CHROMA[i].toByte()).put(CHROMA[i].toByte()).put(255.toByte())
                }
                bytes.flip()
                setupTexture(quantTexture, 8, 8, bytes)
            }
            GlUtil.checkGlError()
            width = w; height = h
        } catch (e: Exception) { deleteTargets(); throw e }
    }

    private fun setupTexture(id: Int, w: Int, h: Int, data: ByteBuffer?) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, data)
    }

    private fun deleteTargets() {
        GLES20.glDeleteFramebuffers(3, fbos, 0)
        GLES20.glDeleteTextures(3, textures, 0)
        fbos = IntArray(3); textures = IntArray(3)
        width = 0; height = 0
    }

    fun release() {
        try { programs.forEach { it.delete() } } finally {
            deleteTargets()
            GLES20.glDeleteTextures(1, intArrayOf(quantTexture), 0)
            quantTexture = 0
        }
    }

    companion object {
        // Conventional JPEG luminance/chrominance quantization tables, in natural order.
        private val LUMA = intArrayOf(
            16,11,10,16,24,40,51,61, 12,12,14,19,26,58,60,55,
            14,13,16,24,40,57,69,56, 14,17,22,29,51,87,80,62,
            18,22,37,56,68,109,103,77, 24,35,55,64,81,104,113,92,
            49,64,78,87,103,121,120,101, 72,92,95,98,112,100,103,99)
        private val CHROMA = intArrayOf(
            17,18,24,47,99,99,99,99, 18,21,26,66,99,99,99,99,
            24,26,56,99,99,99,99,99, 47,66,99,99,99,99,99,99,
            99,99,99,99,99,99,99,99, 99,99,99,99,99,99,99,99,
            99,99,99,99,99,99,99,99, 99,99,99,99,99,99,99,99)
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
            uniform highp sampler2D uTex;
            uniform vec2 uSize, uPadded;
            uniform float uHorizontalBanks;
            #if STAGE == 1
            uniform highp sampler2D uQuant;
            uniform float uScale;
            #endif
            #if STAGE == 3
            uniform highp sampler2D uOriginal, uDeveloped;
            uniform float uMix, uSplit;
            #endif
            varying vec2 vUv;
            float basis(float x, float k) {
                return (k < 0.5 ? 0.35355339059 : 0.5) * cos((2.0*x + 1.0)*k*0.19634954085);
            }
            vec2 packedSize() {
                return uPadded * (uHorizontalBanks > 0.5 ? vec2(2.0,1.0) : vec2(1.0,2.0));
            }
            vec2 bankOffset() {
                return uHorizontalBanks > 0.5 ? vec2(uPadded.x,0.0) : vec2(0.0,uPadded.y);
            }
            vec3 readCoefficient(vec2 p) {
                vec3 hi = texture2D(uTex, (p + 0.5) / packedSize()).rgb;
                vec3 lo = texture2D(uTex, (p + bankOffset() + 0.5) / packedSize()).rgb;
                // Range +/-8 safely covers all intermediate orthonormal DCT values.
                return ((hi * 65280.0 + lo * 255.0) / 65535.0 - 0.5) * 16.0;
            }
            vec4 writeCoefficient(vec3 c, bool lowBank) {
                vec3 code = floor(clamp(c / 16.0 + 0.5, 0.0, 1.0) * 65535.0 + 0.5);
                return vec4((lowBank ? mod(code,256.0) : floor(code/256.0)) / 255.0, 1.0);
            }
            // Work in gamma-encoded YCbCr, where JPEG discards information.
            vec3 readSignal(vec2 p) {
                vec3 rgb = pow(clamp(texture2D(uTex,
                    (clamp(p, vec2(0.0), uSize-1.0)+0.5)/uSize).rgb, 0.0, 1.0), vec3(1.0/2.2));
                return vec3(dot(rgb,vec3(0.299,0.587,0.114))-0.5,
                    dot(rgb,vec3(-0.168736,-0.331264,0.5)),
                    dot(rgb,vec3(0.5,-0.418688,-0.081312)));
            }
            void main() {
                #if STAGE == 3
                vec4 original = texture2D(uOriginal,vUv);
                if (uSplit >= 0.0 && vUv.x < uSplit) { gl_FragColor = original; return; }
                vec2 p = floor(vUv * uSize);
                #else
                vec2 packedPixel = floor(gl_FragCoord.xy);
                bool lowBank = uHorizontalBanks > 0.5 ? packedPixel.x >= uPadded.x : packedPixel.y >= uPadded.y;
                vec2 p = mod(packedPixel,uPadded);
                #endif
                vec2 block = floor(p/8.0)*8.0;
                vec2 local = mod(p,8.0);
                vec3 c = vec3(0.0);
                for (int i=0; i<8; i++) {
                    float n = float(i);
                    #if STAGE == 0
                    c += readSignal(vec2(block.x+n,p.y)) * basis(n,local.x);
                    #elif STAGE == 1
                    c += readCoefficient(vec2(p.x,block.y+n)) * basis(n,local.y);
                    #elif STAGE == 2
                    c += readCoefficient(vec2(p.x,block.y+n)) * basis(local.y,n);
                    #else
                    c += readCoefficient(vec2(block.x+n,p.y)) * basis(local.x,n);
                    #endif
                }
                #if STAGE == 1
                vec3 table = texture2D(uQuant,(local+0.5)/8.0).rgb * 255.0;
                vec3 stepSize = clamp(floor((table*uScale+50.0)/100.0),1.0,255.0)/255.0;
                c = sign(c) * floor(abs(c)/stepSize+0.5) * stepSize;
                #endif
                #if STAGE == 3
                float y = c.r+0.5;
                vec3 rgb = vec3(y+1.402*c.b, y-0.344136*c.g-0.714136*c.b, y+1.772*c.g);
                rgb = pow(clamp(rgb,0.0,1.0),vec3(2.2));
                rgb = mix(texture2D(uDeveloped,vUv).rgb,rgb,uMix);
                if (uSplit >= 0.0 && abs(vUv.x-uSplit) < 1.5/uSize.x) rgb = vec3(0.1,0.8,0.7);
                gl_FragColor = vec4(rgb,original.a);
                #else
                gl_FragColor = writeCoefficient(c,lowBank);
                #endif
            }
        """
    }
}
