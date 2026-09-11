package com.mobicareapp.record

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders one live-captured screen texture into a video encoder's input surface, sampling only a
 * crop sub-rectangle of it (as fractions 0..1 of the full captured frame) rather than the whole
 * thing — this is what lets a screen recording show just the app's own content instead of the
 * full physical display (status bar/nav bar included). Same EGL/shader approach as
 * [com.mobicareapp.edit.PipGlRenderer] (feeding a sub-rectangle of texture coordinates into the
 * same "sample an external OES texture" shader), simplified to one texture since there's nothing
 * to composite here, just crop.
 *
 * The crop is applied in the fragment shader, *after* the source's own texture transform has
 * already produced a correctly-oriented full-frame UV — not by feeding a pre-transform
 * sub-rectangle into the vertex shader. That transform (from [android.graphics.SurfaceTexture])
 * can include a flip or rotation whose exact form isn't guaranteed to be the same across devices
 * or between a live screen capture and a decoded video file; multiplying it against anything but
 * the standard full [0,1] quad risks silently sampling the wrong axis (this is what previously
 * made a video crop come out upside down instead of just cropped). Cropping only after the known-
 * correct full-frame transform sidesteps needing to reason about the matrix's contents at all.
 */
internal class ScreenCropRenderer(outputSurface: Surface) {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private val program: Int
    private val positionHandle: Int
    private val texCoordHandle: Int
    private val mvpMatrixHandle: Int
    private val texMatrixHandle: Int
    private val cropOriginHandle: Int
    private val cropSizeHandle: Int
    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    val textureId: Int

    init {
        setupEgl(outputSurface)

        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        textureId = textureIds[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMvpMatrix")
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
        cropOriginHandle = GLES20.glGetUniformLocation(program, "uCropOrigin")
        cropSizeHandle = GLES20.glGetUniformLocation(program, "uCropSize")

        vertexBuffer = directFloatBuffer(FULL_RECT_COORDS)
        // Always the standard full-frame quad, matched corner-for-corner with FULL_RECT_COORDS
        // (bottom-left, bottom-right, top-left, top-right) — the same pairing used everywhere else
        // in this codebase ([com.mobicareapp.edit.PipGlRenderer], [com.mobicareapp.edit.FrameDecoder]
        // callers) that's known to produce a right-side-up image once uTexMatrix is applied.
        texCoordBuffer = directFloatBuffer(FULL_TEX_COORDS)
    }

    /**
     * [cropLeft]/[cropTop]/[cropRight]/[cropBottom] are fractions (0..1) of the full captured
     * frame as it's actually displayed — e.g. cropTop = statusBarHeight / screenHeight excludes
     * the status bar.
     */
    fun drawFrame(
        textureTransform: FloatArray,
        cropLeft: Float,
        cropTop: Float,
        cropRight: Float,
        cropBottom: Float,
        presentationTimeNs: Long
    ) {
        val (viewportWidth, viewportHeight) = viewportSize()
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, identity, 0)
        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, textureTransform, 0)
        GLES20.glUniform2f(cropOriginHandle, cropLeft, cropTop)
        GLES20.glUniform2f(cropSizeHandle, cropRight - cropLeft, cropBottom - cropTop)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNs)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun viewportSize(): Pair<Int, Int> {
        val widthArr = IntArray(1)
        val heightArr = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, widthArr, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, heightArr, 0)
        return widthArr[0] to heightArr[0]
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun setupEgl(outputSurface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) error("Couldn't get an EGL display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) error("Couldn't initialize EGL")

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            error("Couldn't find a suitable EGL config")
        }
        val config = configs[0] ?: error("Null EGL config")

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) error("Couldn't create an EGL context")

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, outputSurface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) error("Couldn't create an EGL window surface")

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) error("Couldn't make the EGL context current")
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vertexShader)
        GLES20.glAttachShader(prog, fragmentShader)
        GLES20.glLinkProgram(prog)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            error("Couldn't link GL program: $log")
        }
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Couldn't compile shader: $log")
        }
        return shader
    }

    private fun directFloatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(values)
            position(0)
        }

    companion object {
        private val FULL_RECT_COORDS = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )

        // Matches FULL_RECT_COORDS corner-for-corner: bottom-left, bottom-right, top-left, top-right.
        private val FULL_TEX_COORDS = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )

        private const val VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            uniform vec2 uCropOrigin;
            uniform vec2 uCropSize;
            void main() {
                vec2 cropped = uCropOrigin + vTexCoord * uCropSize;
                gl_FragColor = texture2D(sTexture, cropped);
            }
        """
    }
}
