package com.galaxysensorlab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/** Static image input, oriented using EXIF; no source files are overwritten. */
@UnstableApi
object ImagePipeline {
    fun decode(context: Context, uri: Uri): Bitmap {
        val resolver=context.contentResolver
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,bounds) }
        require(bounds.outWidth>0 && bounds.outHeight>0) { "Formato immagine non supportato. Prova JPG, PNG o WebP." }
        require(bounds.outWidth.toLong()*bounds.outHeight<=24_000_000) { "Immagine troppo grande: massimo 24 megapixel." }
        val options=BitmapFactory.Options().apply { inPreferredConfig=Bitmap.Config.ARGB_8888; inPreferredColorSpace=android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB) }
        val bitmap=requireNotNull(resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,options) }) { "Immagine non decodificabile." }
        val orientation=runCatching { resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)
        } }.getOrNull()
        val matrix=Matrix()
        when(orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f,1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f,-1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f,1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f,1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
        }
        if(matrix.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,false).also { if(it!==bitmap) bitmap.recycle() }
    }

    /** Dedicated EGL context, called on a worker thread. Same shader/settings as video. */
    fun render(source: Bitmap, calibration: NoiseCalibration, settings: LabSettings): Bitmap {
        val display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display!=EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display,null,0,null,0)) { "GPU non disponibile per le immagini." }
        var surface=EGL14.EGL_NO_SURFACE
        var context=EGL14.EGL_NO_CONTEXT
        var program: GlProgram?=null
        val texture=IntArray(1)
        try {
            val configs=arrayOfNulls<EGLConfig>(1); val count=IntArray(1)
            check(EGL14.eglChooseConfig(display,intArrayOf(EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,
                EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE),0,configs,0,1,count,0) && count[0]>0)
            context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
            check(context!=EGL14.EGL_NO_CONTEXT)
            surface=EGL14.eglCreatePbufferSurface(display,configs[0],intArrayOf(EGL14.EGL_WIDTH,source.width,EGL14.EGL_HEIGHT,source.height,EGL14.EGL_NONE),0)
            check(surface!=EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display,surface,surface,context)) { "Risoluzione immagine non supportata dalla GPU." }
            val maxSize=IntArray(1); GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE,maxSize,0)
            require(source.width<=maxSize[0] && source.height<=maxSize[0]) { "Immagine oltre il limite GPU di ${maxSize[0]} px per lato." }
            GLES20.glGenTextures(1,texture,0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,source,0)
            program=GlProgram(SensorProgram.VERTEX,SensorProgram.FRAGMENT)
            program.use(); program.setBufferAttribute("aFramePosition",GlUtil.getNormalizedCoordinateBounds(),4)
            setSensorUniforms(program,calibration,settings.sanitizedDefects(),texture[0],source.width,source.height,0,true)
            program.bindAttributesAndUniforms(); GLES20.glViewport(0,0,source.width,source.height)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4); GlUtil.checkGlError()
            val pixels=ByteBuffer.allocateDirect(source.width*source.height*4)
            GLES20.glReadPixels(0,0,source.width,source.height,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels)
            GlUtil.checkGlError()
            // Android Canvas and Compose require premultiplied ARGB bitmaps.
            for(i in 0 until source.width*source.height) {
                val alpha=pixels.get(i*4+3).toInt() and 255
                for(c in 0..2) pixels.put(i*4+c,(((pixels.get(i*4+c).toInt() and 255)*alpha+127)/255).toByte())
            }
            pixels.rewind()
            return Bitmap.createBitmap(source.width,source.height,Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(pixels)
            }
        } finally {
            program?.delete()
            GLES20.glDeleteTextures(1,texture,0)
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            if(surface!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,surface)
            if(context!=EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context)
            // The default display is shared with Media3: keep it alive for the app.
            EGL14.eglReleaseThread()
        }
    }
}
