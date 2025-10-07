package com.adina.flam

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class CameraRenderer(
    private val displayRotation: Int,
    private val onSurfaceTextureCreated: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer {

    private lateinit var surfaceTexture: SurfaceTexture
    private var textureId: Int = 0

    // Matrices for transforming the video feed
    private val mvpMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val textureMatrix = FloatArray(16)

    // Screen and camera dimensions
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0
    private var previewWidth: Int = 0
    private var previewHeight: Int = 0

    // OpenGL program and variable handles
    private var program: Int = 0
    private var positionHandle: Int = 0
    private var texCoordHandle: Int = 0
    private var mvpMatrixHandle: Int = 0
    private var texMatrixHandle: Int = 0

    // Buffers for drawing the image quad
    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer
    private val drawListBuffer: ShortBuffer

    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uTexMatrix;
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTexCoord = (uTexMatrix * aTexCoord).xy;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTexCoord);
        }
    """.trimIndent()

    init {
        // A square that fills the screen in clip space
        val vertices = floatArrayOf(-1.0f, -1.0f, 0.0f, 1.0f, -1.0f, 0.0f, -1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f)
        val texCoords = floatArrayOf(0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f)
        val drawOrder = shortArrayOf(0, 1, 2, 1, 3, 2) // Order to draw vertices

        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices).apply { position(0) }
        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(texCoords).apply { position(0) }
        drawListBuffer = ByteBuffer.allocateDirect(drawOrder.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().put(drawOrder).apply { position(0) }
    }

    fun setCameraPreviewSize(width: Int, height: Int) {
        previewWidth = width
        previewHeight = height
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Create the OpenGL texture that CameraX will draw to
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        // Use high-quality filtering
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        // Create the SurfaceTexture and pass it to MainActivity
        surfaceTexture = SurfaceTexture(textureId)
        onSurfaceTextureCreated(surfaceTexture)

        // Compile shaders and link into a program
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        // Get handles to shader variables
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        surfaceWidth = width
        surfaceHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        // Update the texture with the latest camera frame
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(textureMatrix)

        // Update the transformation matrix
        updateMatrices()

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        // Pass data to the shader
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, textureMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        // Draw the square
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, drawListBuffer)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    private fun updateMatrices() {
        if (surfaceWidth == 0 || surfaceHeight == 0 || previewWidth == 0 || previewHeight == 0) return

        val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        // CameraX provides resolution in the sensor's native orientation (landscape),
        // so we use previewHeight/previewWidth to get the correct aspect ratio for a portrait phone.
        val previewAspect = previewHeight.toFloat() / previewWidth.toFloat()

        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)

        // This projection matrix scales the video to fill the screen, cropping where necessary.
        if (surfaceAspect > previewAspect) {
            val scale = surfaceAspect / previewAspect
            Matrix.orthoM(projectionMatrix, 0, -1f, 1f, -scale, scale, -1f, 1f)
        } else {
            val scale = previewAspect / surfaceAspect
            Matrix.orthoM(projectionMatrix, 0, -scale, scale, -1f, 1f, -1f, 1f)
        }

        val tempMatrix = FloatArray(16)
        Matrix.multiplyMM(tempMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // This rotation handles the device orientation.
        val rotationDegrees = when (displayRotation) {
            Surface.ROTATION_0 -> 0f
            Surface.ROTATION_90 -> 90f
            Surface.ROTATION_180 -> 180f
            Surface.ROTATION_270 -> 270f
            else -> 0f
        }
        Matrix.setRotateM(mvpMatrix, 0, rotationDegrees, 0f, 0f, -1f)
        Matrix.multiplyMM(mvpMatrix, 0, tempMatrix, 0, mvpMatrix, 0)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }
}

