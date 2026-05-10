package com.yuer.magicCamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.view.WindowCompat
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var cameraPreview: PreviewView
    private lateinit var detectionView: ImageView
    private lateinit var shutterBtn: ImageButton

    private lateinit var cameraExecutor: ExecutorService

    private val cameraPermissionRequest = 100

    private var processingThread: HandlerThread? = null
    private var processingHandler: Handler? = null

    private val isProcessing = AtomicBoolean(false)

    private var previewWidth = 640
    private var previewHeight = 480

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        @Suppress("DEPRECATION")
        if (!OpenCVLoader.initDebug()) {
            Toast.makeText(this, "OpenCV init failed", Toast.LENGTH_SHORT).show()
        }

        cameraPreview = findViewById(R.id.camera_preview)
        detectionView = findViewById(R.id.detection_view)
        shutterBtn = findViewById(R.id.btn_shutter)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                cameraPermissionRequest
            )
        } else {
            startCamera()
        }

        processingThread = HandlerThread("CardDetection").apply {
            start()
            processingHandler = Handler(looper)
        }
    }

    private fun startCamera() {

        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({

            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()
            preview.surfaceProvider = cameraPreview.surfaceProvider

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->

                if (!isProcessing.compareAndSet(false, true)) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                processingHandler?.post {

                    try {
                        previewWidth = imageProxy.width
                        previewHeight = imageProxy.height

                        val nv21 = imageProxyToNv21(imageProxy)

                        detectPokerCard(nv21)

                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        isProcessing.set(false)
                        imageProxy.close()
                    }
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalysis
            )

        }, ContextCompat.getMainExecutor(this))
    }

    private fun imageProxyToNv21(image: ImageProxy): ByteArray {

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        return nv21
    }

    // =========================
    // ⭐ 核心统一坐标函数（关键）
    // =========================
    private fun normalizeFrame(data: ByteArray): Mat {

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

        // 统一竖屏
        Core.rotate(rgb, rotated, Core.ROTATE_90_CLOCKWISE)
        rgb.release()

        // ⭐ 前摄镜像修正（统一规则）
        Core.flip(rotated, rotated, 0)

        return rotated
    }

    private fun detectPokerCard(data: ByteArray) {

        val rotated = normalizeFrame(data)

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

        val bmp = createBitmap(rotated.cols(), rotated.rows())

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

        processingThread?.quitSafely()
        cameraExecutor.shutdown()
    }
}