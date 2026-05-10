package com.yuer.magicCamera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.Camera
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.TextureView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity(), Camera.PreviewCallback {

    private var camera: Camera? = null

    private var cameraId = Camera.CameraInfo.CAMERA_FACING_FRONT

    private lateinit var cameraPreview: TextureView
    private lateinit var detectionView: ImageView
    private lateinit var shutterBtn: ImageButton

    private val CAMERA_PERMISSION_REQUEST = 100

    private var processingThread: HandlerThread? = null
    private var processingHandler: Handler? = null

    private val isProcessing = AtomicBoolean(false)

    private var previewWidth = 640
    private var previewHeight = 480

    private lateinit var surfaceTextureListener: TextureView.SurfaceTextureListener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ 沉浸式 + 透明状态栏
        window.apply {

            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT

            setFlags(
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )

            decorView.systemUiVisibility =
                (android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }

        supportActionBar?.hide()

        setContentView(R.layout.activity_main)

        // OpenCV
        if (!OpenCVLoader.initDebug()) {
            Toast.makeText(this, "OpenCV init failed", Toast.LENGTH_SHORT).show()
        }

        cameraPreview = findViewById(R.id.camera_preview)
        detectionView = findViewById(R.id.detection_view)
        shutterBtn = findViewById(R.id.btn_shutter)

        shutterBtn.setOnClickListener {
            // 暂无逻辑
        }

        // TextureView
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {

            override fun onSurfaceTextureAvailable(
                surface: android.graphics.SurfaceTexture,
                width: Int,
                height: Int
            ) {
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(
                surface: android.graphics.SurfaceTexture,
                width: Int,
                height: Int
            ) {}

            override fun onSurfaceTextureDestroyed(
                surface: android.graphics.SurfaceTexture
            ): Boolean {
                releaseCamera()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
        }

        if (cameraPreview.isAvailable) {
            openCamera()
        } else {
            cameraPreview.surfaceTextureListener = surfaceTextureListener
        }

        // 权限
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST
            )
        }

        // 线程
        processingThread = HandlerThread("CardDetection").apply {
            start()
            processingHandler = Handler(looper)
        }
    }

    private fun openCamera() {

        try {
            camera = Camera.open(cameraId)

            val params = camera!!.parameters
            val size = params.previewSize

            previewWidth = size.width
            previewHeight = size.height

            val info = Camera.CameraInfo()
            Camera.getCameraInfo(cameraId, info)

            val rotation = windowManager.defaultDisplay.rotation

            val degrees = when (rotation) {
                0 -> 0
                1 -> 90
                2 -> 180
                3 -> 270
                else -> 0
            }

            val result =
                (360 - (info.orientation + degrees) % 360) % 360

            camera!!.setDisplayOrientation(result)

            val bufferSize = previewWidth * previewHeight * 3 / 2

            camera!!.addCallbackBuffer(ByteArray(bufferSize))
            camera!!.setPreviewCallbackWithBuffer(this)
            camera!!.setPreviewTexture(cameraPreview.surfaceTexture)
            camera!!.startPreview()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseCamera() {
        camera?.stopPreview()
        camera?.setPreviewCallbackWithBuffer(null)
        camera?.release()
        camera = null
    }

    override fun onPreviewFrame(data: ByteArray, camera: Camera) {

        if (!isProcessing.compareAndSet(false, true)) return

        processingHandler?.post {
            try {
                detectPokerCard(data)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isProcessing.set(false)
                camera.addCallbackBuffer(data)
            }
        }
    }

    private fun detectPokerCard(data: ByteArray) {

        val yuv = Mat(
            previewHeight + previewHeight / 2,
            previewWidth,
            CvType.CV_8UC1
        )

        yuv.put(0, 0, data)

        val rgb = Mat()
        Imgproc.cvtColor(yuv, rgb, Imgproc.COLOR_YUV2RGB_NV21)
        yuv.release()

        val rotated = Mat()
        Core.transpose(rgb, rotated)
        Core.flip(rotated, rotated, 0)
        rgb.release()

        val hsv = Mat()
        Imgproc.cvtColor(rotated, hsv, Imgproc.COLOR_RGB2HSV)

        val lower = Scalar(0.0, 0.0, 180.0)
        val upper = Scalar(180.0, 60.0, 255.0)

        val mask = Mat()
        Core.inRange(hsv, lower, upper, mask)
        hsv.release()

        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(5.0, 5.0)
        )

        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()

        Imgproc.findContours(
            mask,
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        for (c in contours) {

            val area = Imgproc.contourArea(c)
            if (area < 8000) continue

            val rect = Imgproc.minAreaRect(
                MatOfPoint2f(*c.toArray())
            )

            val w = rect.size.width
            val h = rect.size.height

            val ratio = max(w, h) / min(w, h)
            if (ratio !in 1.3..2.0) continue

            val pts = arrayOfNulls<Point>(4)
            rect.points(pts)

            for (i in 0..3) {
                Imgproc.line(
                    rotated,
                    pts[i]!!,
                    pts[(i + 1) % 4]!!,
                    Scalar(0.0, 255.0, 0.0),
                    3
                )
            }
        }

        val bmp = Bitmap.createBitmap(
            rotated.cols(),
            rotated.rows(),
            Bitmap.Config.ARGB_8888
        )

        org.opencv.android.Utils.matToBitmap(rotated, bmp)

        runOnUiThread {
            detectionView.setImageBitmap(bmp)
        }

        rotated.release()
        mask.release()
        kernel.release()
        hierarchy.release()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseCamera()
        processingThread?.quitSafely()
    }
}