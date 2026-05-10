package com.yuer.magicCamera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.*
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
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

    private var processingThread: HandlerThread? = null
    private var processingHandler: Handler? = null

    private val isProcessing = AtomicBoolean(false)

    private var previewWidth = 0
    private var previewHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // =========================
        // ⭐ 全屏沉浸（隐藏状态栏+导航栏）
        // =========================
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemUI()

        supportActionBar?.hide()

        setContentView(R.layout.activity_main)

        cameraPreview = findViewById(R.id.camera_preview)
        detectionView = findViewById(R.id.detection_view)
        shutterBtn = findViewById(R.id.btn_shutter)

        // 防止拉伸
        cameraPreview.scaleType = PreviewView.ScaleType.FIT_CENTER
        detectionView.scaleType = ImageView.ScaleType.FIT_CENTER
        detectionView.adjustViewBounds = true

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (!OpenCVLoader.initDebug()) {
            Toast.makeText(this, "OpenCV init failed", Toast.LENGTH_SHORT).show()
        }

        processingThread = HandlerThread("CardDetection").apply {
            start()
            processingHandler = Handler(looper)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                100
            )
        } else {
            startCamera()
        }
    }

    // =========================
    // ⭐ 隐藏状态栏/导航栏
    // =========================
    private fun hideSystemUI() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    // =========================
    // CameraX
    // =========================
    private fun startCamera() {

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({

            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().apply {
                surfaceProvider = cameraPreview.surfaceProvider
            }

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

    // =========================
    // YUV -> NV21
    // =========================
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
    // ⭐ 坐标统一（已修正镜像逻辑）
    // =========================
    private fun normalizeFrame(data: ByteArray): Mat {

        val yuv = Mat(previewHeight + previewHeight / 2, previewWidth, CvType.CV_8UC1)
        yuv.put(0, 0, data)

        val rgb = Mat()
        Imgproc.cvtColor(yuv, rgb, Imgproc.COLOR_YUV2RGB_NV21)
        yuv.release()

        val rotated = Mat()

        // 竖屏
        Core.rotate(rgb, rotated, Core.ROTATE_90_CLOCKWISE)
        rgb.release()

        // ⭐ 前摄：只做“上下翻转”（不做左右翻转！）
        Core.flip(rotated, rotated, 0)

        return rotated
    }

    // =========================
    // 检测逻辑
    // =========================
    private fun detectPokerCard(data: ByteArray) {

        val frame = normalizeFrame(data)

        val hsv = Mat()
        Imgproc.cvtColor(frame, hsv, Imgproc.COLOR_RGB2HSV)

        val mask = Mat()
        Core.inRange(
            hsv,
            Scalar(0.0, 0.0, 180.0),
            Scalar(180.0, 60.0, 255.0),
            mask
        )
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

            val rect = Imgproc.minAreaRect(MatOfPoint2f(*c.toArray()))

            val w = rect.size.width
            val h = rect.size.height

            val ratio = max(w, h) / min(w, h)
            if (ratio !in 1.3..2.0) continue

            val pts = arrayOfNulls<Point>(4)
            rect.points(pts)

            for (i in 0..3) {
                Imgproc.line(
                    frame,
                    pts[i]!!,
                    pts[(i + 1) % 4]!!,
                    Scalar(0.0, 255.0, 0.0),
                    3
                )
            }
        }

        val bmp = Bitmap.createBitmap(
            frame.cols(),
            frame.rows(),
            Bitmap.Config.ARGB_8888
        )

        Utils.matToBitmap(frame, bmp)

        runOnUiThread {
            detectionView.setImageBitmap(bmp)
        }

        frame.release()
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