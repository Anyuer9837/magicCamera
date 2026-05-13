package com.yuer.magicCamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaActionSound
import android.os.*
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import androidx.core.graphics.createBitmap

class MainActivity : AppCompatActivity() {

    private lateinit var cameraPreview: PreviewView
    private lateinit var detectionView: ImageView
    private lateinit var shutterBtn: ImageButton
    private lateinit var switchCameraBtn: ImageButton
    private lateinit var flashOverlay: View

    private lateinit var cameraExecutor: ExecutorService

    private var processingThread: HandlerThread? = null
    private var processingHandler: Handler? = null

    private val isProcessing = AtomicBoolean(false)

    private var previewWidth = 0
    private var previewHeight = 0

    // 用于缓存替换图片 Mat
    private var replacementCardMat: Mat? = null

    // 本地数据存储
    private lateinit var sharedPrefs: SharedPreferences

    // ⭐ 系统快门音效播放器
    private var mediaActionSound: MediaActionSound? = null

    // 防闪烁缓存变量
    private var lastTl: Point? = null
    private var lastTr: Point? = null
    private var lastBl: Point? = null
    private var lastBr: Point? = null
    private var missedFrames = 1
    private val MAX_MISSED_FRAMES = 3

    private var isDebugMode = false
    private var lensFacing = CameraSelector.LENS_FACING_FRONT

    // 可调算法参数
    @Volatile private var blurSize: Double = 13.0
    @Volatile private var cannyLower: Double = 0.0
    @Volatile private var cannyUpper: Double = 150.0
    @Volatile private var morphKernelSize: Double = 30.0

    // =========================
    // 魔术流程控制状态机
    // 0 = 待机 (不贴图)
    // 1 = 选花色中
    // 2 = 选点数中
    // 3 = 激活贴图
    // =========================
    private var magicState = 0
    private var selectedSuit = -1
    private var selectedRank = -1

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPrefs = getSharedPreferences("MagicCameraParams", Context.MODE_PRIVATE)
        loadParamsForCurrentCamera()

        // ⭐ 提前加载相机快门音效，防止第一次按时延迟
        mediaActionSound = MediaActionSound()
        mediaActionSound?.load(MediaActionSound.SHUTTER_CLICK)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemUI()
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        cameraPreview = findViewById(R.id.camera_preview)
        detectionView = findViewById(R.id.detection_view)
        shutterBtn = findViewById(R.id.btn_shutter)
        switchCameraBtn = findViewById(R.id.btn_switch_camera)
        flashOverlay = findViewById(R.id.flash_overlay)

        cameraPreview.scaleType = PreviewView.ScaleType.FIT_CENTER
        detectionView.scaleType = ImageView.ScaleType.FIT_CENTER
        detectionView.adjustViewBounds = true

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (!OpenCVLoader.initDebug()) {
            Toast.makeText(this, "OpenCV init failed", Toast.LENGTH_SHORT).show()
        }

        // ==========================================
        // ⭐ 短按快门：展现真实拍照效果，并进入暗号状态
        // ==========================================
        shutterBtn.setOnClickListener {
            // 1. 给观众看的：触发快门动画和音效
            triggerRealShutterEffect()

            // 2. 给魔术师的：进入暗号流程并用弱震动提示
            if (magicState == 0 || magicState == 3) {
                magicState = 1 // 进入选花色状态
            }
        }

        // 长按快门：呼出参数设置面板
        shutterBtn.setOnLongClickListener {
            showSettingsDialog()
            true
        }

        // 短按：翻转摄像头
        switchCameraBtn.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            loadParamsForCurrentCamera()
            clearCache()
            startCamera()
        }

        // 长按切换摄像头按钮：开关调试模式
        switchCameraBtn.setOnLongClickListener {
            isDebugMode = !isDebugMode
            val msg = if (isDebugMode) "调试模式：开启 (显示所有轮廓)" else "调试模式：关闭 (AR替换)"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            clearCache()
            true
        }

        // ==========================================
        // 核心逻辑：拦截屏幕触摸，进行暗号选牌
        // ==========================================
        detectionView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val x = event.x
                val y = event.y
                val w = v.width.toFloat()
                val h = v.height.toFloat()

                if (magicState == 1) {
                    // 【阶段1：选花色】2x2 四宫格
                    selectedSuit = when {
                        x < w / 2 && y < h / 2 -> 0   // 左上：黑桃
                        x >= w / 2 && y < h / 2 -> 1  // 右上：红心
                        x < w / 2 && y >= h / 2 -> 2  // 左下：梅花
                        else -> 3                     // 右下：方块
                    }
                    magicState = 2 // 自动进入下一阶段
                    return@setOnTouchListener true
                }
                else if (magicState == 2) {
                    // 【阶段2：选点数】3x5网格 (屏幕前80%高度) + 底部区域
                    if (y < h * 0.8f) {
                        // 前4排：每排3个 (A~12)
                        val row = (y / (h * 0.2f)).toInt().coerceIn(0, 3)
                        val col = (x / (w / 3.0f)).toInt().coerceIn(0, 2)
                        selectedRank = row * 3 + col + 1 // 1 到 12
                    } else {
                        // 最后一排 (h * 0.8 ~ 底部)
                        if (x < w / 3.0f) {
                            selectedRank = 13 // 屏幕左下角区域（快门左侧）：K
                        } else {
                            // 如果误触了中间（快门）或右边，无视
                            return@setOnTouchListener true
                        }
                    }

                    // 完成选牌：计算最终ID并加载图片
                    val finalCardId = selectedSuit * 13 + selectedRank
                    loadSelectedCardImage(finalCardId)

                    magicState = 3 // 激活AR贴图

                    // 暗示选好牌了：黑屏消失一帧
                    triggerSecretFlash()
                    return@setOnTouchListener true
                }
            }
            false
        }

        processingThread = HandlerThread("CardDetection").apply {
            start()
            processingHandler = Handler(looper)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), 100
            )
        } else {
            startCamera()
        }
    }

    // ==========================================
    // ⭐ 真实快门动画（给观众看）
    // ==========================================
    private fun triggerRealShutterEffect() {
        // 播放系统拍照咔嚓声
        mediaActionSound?.play(MediaActionSound.SHUTTER_CLICK)

        // 停止之前的暗号动画防止冲突
        flashOverlay.animate().cancel()
        flashOverlay.alpha = 1f
        flashOverlay.visibility = View.VISIBLE

        // 执行 200 毫秒的淡出动画，模仿真实快门
        flashOverlay.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                flashOverlay.visibility = View.GONE
                flashOverlay.alpha = 1f
            }
            .start()
    }

    // ==========================================
    // ⭐ 暗号反馈：让画面瞬间变黑一帧（极短，魔术师可见）
    // ==========================================
    private fun triggerSecretFlash() {
        flashOverlay.animate().cancel()
        flashOverlay.alpha = 1f
        flashOverlay.visibility = View.VISIBLE
        Handler(Looper.getMainLooper()).postDelayed({
            flashOverlay.visibility = View.GONE
        }, 50)
    }

    // ==========================================
    // 动态加载选中的卡牌图片
    // ==========================================
    private fun loadSelectedCardImage(cardId: Int) {
        val resName = "card_$cardId"
        val resId = resources.getIdentifier(resName, "drawable", packageName)

        if (resId != 0) {
            val bitmap = BitmapFactory.decodeResource(resources, resId)
            val newMat = Mat()
            Utils.bitmapToMat(bitmap, newMat)
            Imgproc.cvtColor(newMat, newMat, Imgproc.COLOR_RGBA2RGB)

            // 安全替换旧矩阵
            val oldMat = replacementCardMat
            replacementCardMat = newMat
            oldMat?.release()
        } else {
            runOnUiThread {
                Toast.makeText(this, "严重错误: 未找到图片资源 $resName", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun clearCache() {
        lastTl = null; lastTr = null; lastBl = null; lastBr = null; missedFrames = 0
    }

    // ---------------- 参数存储与读取 ----------------
    private fun getPrefPrefix(): String = if (lensFacing == CameraSelector.LENS_FACING_FRONT) "front_" else "back_"

    private fun loadParamsForCurrentCamera() {
        val prefix = getPrefPrefix()
        blurSize = sharedPrefs.getFloat(prefix + "blurSize", 13.0f).toDouble()
        cannyLower = sharedPrefs.getFloat(prefix + "cannyLower", 0.0f).toDouble()
        cannyUpper = sharedPrefs.getFloat(prefix + "cannyUpper", 150.0f).toDouble()
        morphKernelSize = sharedPrefs.getFloat(prefix + "morphKernelSize", 30.0f).toDouble()
    }

    private fun saveParamsToLocal() {
        val prefix = getPrefPrefix()
        sharedPrefs.edit().apply {
            putFloat(prefix + "blurSize", blurSize.toFloat())
            putFloat(prefix + "cannyLower", cannyLower.toFloat())
            putFloat(prefix + "cannyUpper", cannyUpper.toFloat())
            putFloat(prefix + "morphKernelSize", morphKernelSize.toFloat())
            apply()
        }
    }

    private fun showSettingsDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
        }

        val tvBlur = TextView(this).apply { text = "高斯模糊大小: ${blurSize.toInt()}" }
        val sbBlur = SeekBar(this).apply {
            max = 20
            progress = (blurSize.toInt() - 1) / 2
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val actualVal = progress * 2 + 1
                    tvBlur.text = "高斯模糊大小: $actualVal"
                    blurSize = actualVal.toDouble()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        val tvCannyL = TextView(this).apply { text = "Canny 下限: ${cannyLower.toInt()}" ; setPadding(0, 30, 0, 0)}
        val sbCannyL = SeekBar(this).apply {
            max = 255
            progress = cannyLower.toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    tvCannyL.text = "Canny 下限: $progress"
                    cannyLower = progress.toDouble()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        val tvCannyU = TextView(this).apply { text = "Canny 上限: ${cannyUpper.toInt()}" ; setPadding(0, 30, 0, 0)}
        val sbCannyU = SeekBar(this).apply {
            max = 255
            progress = cannyUpper.toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    tvCannyU.text = "Canny 上限: $progress"
                    cannyUpper = progress.toDouble()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        val tvKernel = TextView(this).apply { text = "闭运算补缝大小 (Kernel): ${morphKernelSize.toInt()}" ; setPadding(0, 30, 0, 0)}
        val sbKernel = SeekBar(this).apply {
            max = 99
            progress = morphKernelSize.toInt() - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val actualVal = progress + 1
                    tvKernel.text = "闭运算补缝大小 (Kernel): $actualVal"
                    morphKernelSize = actualVal.toDouble()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        layout.addView(tvBlur)
        layout.addView(sbBlur)
        layout.addView(tvCannyL)
        layout.addView(sbCannyL)
        layout.addView(tvCannyU)
        layout.addView(sbCannyU)
        layout.addView(tvKernel)
        layout.addView(sbKernel)

        val titlePrefix = if (lensFacing == CameraSelector.LENS_FACING_FRONT) "前置" else "后置"
        AlertDialog.Builder(this)
            .setTitle("$titlePrefix 摄像头参数调节")
            .setView(layout)
            .setPositiveButton("确定") { _, _ -> saveParamsToLocal() }
            .setOnDismissListener { saveParamsToLocal() }
            .show()
    }

    private fun hideSystemUI() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

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
                        detectObjectContours(nv21)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        isProcessing.set(false)
                        imageProxy.close()
                    }
                }
            }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) {
                Toast.makeText(this, "相机切换失败\n$e", Toast.LENGTH_SHORT).show()
            }

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

    private fun normalizeFrame(data: ByteArray): Mat {
        val yuv = Mat(previewHeight + previewHeight / 2, previewWidth, CvType.CV_8UC1)
        yuv.put(0, 0, data)

        val rgb = Mat()
        Imgproc.cvtColor(yuv, rgb, Imgproc.COLOR_YUV2RGB_NV21)
        yuv.release()

        val rotated = Mat()
        Core.rotate(rgb, rotated, Core.ROTATE_90_CLOCKWISE)
        rgb.release()

        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            Core.flip(rotated, rotated, 0)
        }
        return rotated
    }

    // =========================
    // 检测逻辑
    // =========================
    private fun detectObjectContours(data: ByteArray) {

        val frame = normalizeFrame(data)
        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val hierarchy = Mat()

        Imgproc.cvtColor(frame, gray, Imgproc.COLOR_RGB2GRAY)

        Imgproc.GaussianBlur(gray, blurred, Size(blurSize, blurSize), 0.0)
        Imgproc.Canny(blurred, edges, cannyLower, cannyUpper)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(morphKernelSize, morphKernelSize))
        Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(
            edges,
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        if (isDebugMode) {
            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area > 10000.0) {
                    Imgproc.drawContours(frame, listOf(contour), 0, Scalar(255.0, 255.0, 0.0), 2)
                    val rect = Imgproc.boundingRect(contour)
                    Imgproc.rectangle(frame, rect.tl(), rect.br(), Scalar(0.0, 255.0, 0.0), 3)
                }
            }
        } else {
            var bestContour: MatOfPoint? = null

            val frameCenterX = frame.cols() / 2.0
            val frameCenterY = frame.rows() / 2.0

            var minCenterDist = Double.MAX_VALUE
            var maxArea = 0.0

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)

                if (area > 10000.0) {
                    val contour2f = MatOfPoint2f(*contour.toArray())
                    val minRect = Imgproc.minAreaRect(contour2f)

                    if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                        val dist = Math.hypot(minRect.center.x - frameCenterX, minRect.center.y - frameCenterY)
                        if (dist < minCenterDist) {
                            minCenterDist = dist
                            bestContour = contour
                        }
                    } else {
                        val w = minRect.size.width
                        val h = minRect.size.height

                        if (w > 0 && h > 0) {
                            val ratio = w.coerceAtLeast(h) / w.coerceAtMost(h)
                            val rectArea = w * h
                            val extent = area / rectArea

                            if (ratio in 1.3..1.9 && extent > 0.73) {
                                if (area > maxArea) {
                                    maxArea = area
                                    bestContour = contour
                                }
                            }
                        }
                    }
                    contour2f.release()
                }
            }

            if (bestContour != null) {
                val points = bestContour.toArray()

                var tl = points[0]
                var tr = points[0]
                var bl = points[0]
                var br = points[0]

                if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                    var minSum = Double.MAX_VALUE
                    var maxDiff = -Double.MAX_VALUE
                    var minDiff = Double.MAX_VALUE

                    for (p in points) {
                        val sum = p.x + p.y
                        val diff = p.x - p.y

                        if (sum < minSum) { minSum = sum; tl = p }
                        if (diff > maxDiff) { maxDiff = diff; tr = p }
                        if (diff < minDiff) { minDiff = diff; bl = p }
                    }

                    br = Point(tr.x + bl.x - tl.x, tr.y + bl.y - tl.y)

                } else {
                    var minSum = Double.MAX_VALUE
                    var maxSum = -Double.MAX_VALUE
                    var maxDiff = -Double.MAX_VALUE
                    var minDiff = Double.MAX_VALUE

                    for (p in points) {
                        val sum = p.x + p.y
                        val diff = p.x - p.y

                        if (sum < minSum) { minSum = sum; tl = p }
                        if (sum > maxSum) { maxSum = sum; br = p }
                        if (diff > maxDiff) { maxDiff = diff; tr = p }
                        if (diff < minDiff) { minDiff = diff; bl = p }
                    }
                }

                lastTl = tl
                lastTr = tr
                lastBl = bl
                lastBr = br
                missedFrames = 0

                // ⭐ 只有魔术状态为 3（已完成选牌），才执行真实替换！
                if (magicState == 3) {
                    replaceDetectedCard(frame, tl, tr, br, bl)
                }

            } else {
                if (lastTl != null && missedFrames < MAX_MISSED_FRAMES) {
                    missedFrames++
                    if (magicState == 3) replaceDetectedCard(frame, lastTl!!, lastTr!!, lastBr!!, lastBl!!)
                } else {
                    clearCache()
                }
            }
        }

        val bmp = createBitmap(frame.cols(), frame.rows())
        Utils.matToBitmap(frame, bmp)

        runOnUiThread {
            detectionView.setImageBitmap(bmp)
        }

        frame.release()
        gray.release()
        blurred.release()
        edges.release()
        kernel.release()
        hierarchy.release()
    }

    private fun replaceDetectedCard(frame: Mat, tl: Point, tr: Point, br: Point, bl: Point) {
        if (replacementCardMat == null) return

        val srcMat = replacementCardMat!!
        val w = srcMat.cols().toDouble()
        val h = srcMat.rows().toDouble()

        val srcPts = MatOfPoint2f(
            Point(0.0, 0.0), Point(w, 0.0), Point(w, h), Point(0.0, h)
        )
        val dstPts = MatOfPoint2f(tl, tr, br, bl)

        val transformMatrix = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val warpedCard = Mat()
        Imgproc.warpPerspective(srcMat, warpedCard, transformMatrix, frame.size())

        val mask = Mat.zeros(frame.size(), CvType.CV_8UC1)
        val maskPolygon = listOf(MatOfPoint(tl, tr, br, bl))
        Imgproc.fillPoly(mask, maskPolygon, Scalar(255.0))

        warpedCard.copyTo(frame, mask)

        warpedCard.release()
        mask.release()
        transformMatrix.release()
        srcPts.release()
        dstPts.release()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaActionSound?.release()
        replacementCardMat?.release()
        replacementCardMat = null
        processingThread?.quitSafely()
        cameraExecutor.shutdown()
    }
}