package com.adina.flam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var cameraRenderer: CameraRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on and set to fullscreen
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        makeFullscreen()

        setContentView(R.layout.activity_main)

        // Initialize views and executor
        glSurfaceView = findViewById(R.id.glSurfaceView)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Check for camera permission before setting up the camera
        if (allPermissionsGranted()) {
            setupCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun makeFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun setupCamera() {
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setPreserveEGLContextOnPause(true)

        // Get display rotation and create the renderer
        val rotation = windowManager.defaultDisplay.rotation
        cameraRenderer = CameraRenderer(rotation) { surfaceTexture ->
            startCamera(surfaceTexture)
        }

        glSurfaceView.setRenderer(cameraRenderer)
        // Set to continuous rendering for a smooth video feed
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    private fun startCamera(surfaceTexture: SurfaceTexture) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Build a high-quality camera preview
            val preview = Preview.Builder()
                .setTargetRotation(windowManager.defaultDisplay.rotation)
                .build()
                .also {
                    it.setSurfaceProvider { request ->
                        val resolution = request.resolution
                        // Pass camera resolution to the renderer for aspect ratio calculations
                        cameraRenderer.setCameraPreviewSize(resolution.width, resolution.height)
                        surfaceTexture.setDefaultBufferSize(resolution.width, resolution.height)

                        val surface = android.view.Surface(surfaceTexture)
                        request.provideSurface(surface, cameraExecutor) {
                            surface.release()
                        }
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                setupCamera()
            } else {
                Log.e(TAG, "Permissions not granted by the user.")
                finish()
            }
        }
    }

    // Handle GLSurfaceView lifecycle
    override fun onResume() {
        super.onResume()
        if (::glSurfaceView.isInitialized) {
            glSurfaceView.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::glSurfaceView.isInitialized) {
            glSurfaceView.onPause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "FlamApp"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        init {
            System.loadLibrary("flam")
        }
    }

    private external fun stringFromJNI(): String
}

