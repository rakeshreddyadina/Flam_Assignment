package com.adina.flam

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class CameraRenderer(
    private val onSurfaceTextureCreated: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer {

    private lateinit var surfaceTexture: SurfaceTexture
    private var textureId: Int = 0

    private val transformMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var cameraWidth: Int = 0
    private var cameraHeight: Int = 0

    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        varying vec2 vTexCoord;
        uniform mat4 uMVPMatrix;
        uniform mat4 uTexMatrix;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTexCoord = (uTexMatrix * aTexCoord).xy;
        }
    """

    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTexCoord);
        }
    """

    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    private var program: Int = 0
    private var mvpMatrixHandle: Int = 0
    private var texMatrixHandle: Int = 0
    private var positionHandle: Int = 0
    private var texCoordHandle: Int = 0

    init {
        // Full screen quad vertices
        val vertices = floatArrayOf(
            -1.0f, -1.0f,  // Bottom-left
            1.0f, -1.0f,  // Bottom-right
            -1.0f,  1.0f,  // Top-left
            1.0f,  1.0f   // Top-right
        )

        // Texture coordinates
        val texCoords = floatArrayOf(
            0.0f, 0.0f,  // Bottom-left
            1.0f, 0.0f,  // Bottom-right
            0.0f, 1.0f,  // Top-left
            1.0f, 1.0f   // Top-right
        )

        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices).apply { position(0) }

        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(texCoords).apply { position(0) }
    }

    fun setCameraPreviewSize(width: Int, height: Int) {
        cameraWidth = width
        cameraHeight = height
        Log.d(TAG, "Camera preview size set: ${width}x${height}")
        updateMatrices()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        program = createProgram(vertexShaderCode, fragmentShaderCode)

        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(textureId)
        onSurfaceTextureCreated(surfaceTexture)

        Log.d(TAG, "Surface created successfully")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
        GLES20.glViewport(0, 0, width, height)
        Log.d(TAG, "Surface changed: ${width}x${height}")
        updateMatrices()
    }

    override fun onDrawFrame(gl: GL10?) {
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(transformMatrix)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        // Enable vertex arrays
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)

        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)

        // Set matrices
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, transformMatrix, 0)

        // Bind texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        // Draw
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Cleanup
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    private fun updateMatrices() {
        if (screenWidth == 0 || screenHeight == 0 || cameraWidth == 0 || cameraHeight == 0) {
            Matrix.setIdentityM(mvpMatrix, 0)
            return
        }

        // Calculate aspect ratios
        val screenAspect = screenWidth.toFloat() / screenHeight.toFloat()
        // Camera gives us width x height, but the image is rotated 90 degrees
        // So we need to swap the dimensions for aspect ratio calculation
        val cameraAspect = cameraHeight.toFloat() / cameraWidth.toFloat()

        Log.d(TAG, "Screen aspect: $screenAspect (${screenWidth}x${screenHeight})")
        Log.d(TAG, "Camera aspect: $cameraAspect (${cameraWidth}x${cameraHeight})")

        // Calculate scale to fill screen (crop mode)
        val scaleX: Float
        val scaleY: Float

        if (screenAspect > cameraAspect) {
            // Screen is wider than camera - fit width, crop height
            scaleX = 1.0f
            scaleY = screenAspect / cameraAspect
        } else {
            // Screen is taller than camera - fit height, crop width
            scaleX = cameraAspect / screenAspect
            scaleY = 1.0f
        }

        Log.d(TAG, "Scale: X=$scaleX, Y=$scaleY")

        // Build MVP matrix
        Matrix.setIdentityM(mvpMatrix, 0)
        Matrix.scaleM(mvpMatrix, 0, scaleX, scaleY, 1.0f)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)

        return GLES20.glCreateProgram().also { program ->
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)

            // Check for link errors
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                Log.e(TAG, "Error linking program: ${GLES20.glGetProgramInfoLog(program)}")
                GLES20.glDeleteProgram(program)
            }

            // Clean up shaders
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)

            // Check for compile errors
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e(TAG, "Error compiling shader: ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
            }
        }
    }

    companion object {
        private const val TAG = "CameraRenderer"
    }
}