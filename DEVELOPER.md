# magicCamera — 程序员文档

> 本文档面向开发爱好者，说明项目的技术架构、算法实现和开发流程。
---

# 效果演示

<img width="1599" height="2844" alt="7af82f4b-6df2-4af5-b752-45ee1b9d15bf" src="https://github.com/user-attachments/assets/6f05c1b8-9ecf-4b0a-ae84-6804e04ff4aa" />

## 📋 项目概述

**magicCamera** 是一个 Android 应用，利用摄像头、OpenCV 图像处理和 CameraX 框架，实现实时卡牌检测和 AR 替换。

### 核心功能

- 📷 **实时卡牌检测**：使用 Canny 边界检测和图像处理算法检测视野中的卡牌
- 🎴 **AR 卡牌替换**：将检测到的卡牌实时替换为指定的卡牌图像
- 🎯 **交互式选牌流程**：通过屏幕触摸进行两步选牌（先选花色，再选点数）
- ⚙️ **动态参数调节**：长按快门按钮呼出参数面板，实时调整检测算法参数
- 🔄 **双摄像头支持**：前后摄像头切换，每个摄像头独立保存参数配置

---

## 🏗️ 技术架构

### 核心库和框架

| 库/框架 | 用途 |
|--------|------|
| **CameraX** | 相机捕获和预览管理 |
| **OpenCV** | 图像处理和卡牌检测 |
| **Kotlin Coroutines** | 异步任务处理 |
| **AndroidX AppCompat** | Material Design UI 组件 |
| **SharedPreferences** | 参数持久化存储 |

### 核心类结构

```
MainActivity.kt
├── 相机管理
│   ├── ProcessCameraProvider
│   ├── CameraSelector (前/后)
│   └── ImageAnalysis
│
├── 图像处理
│   ├── imageProxyToNv21()          [YUV → NV21 转换]
│   ├── normalizeFrame()             [颜色空间转换和旋转]
│   └── detectObjectContours()       [核心检测逻辑]
│
├── 卡牌替换
│   ├── replaceDetectedCard()        [透视变换]
│   └── loadSelectedCardImage()      [加载卡牌图片]
│
├── UI 交互
│   ├── shutterBtn.setOnClickListener()
│   ├── switchCameraBtn.setOnClickListener()
│   ├── detectionView.setOnTouchListener()
│   └── showSettingsDialog()         [参数调节面板]
│
└── 状态管理
    └── magicState (0=待机, 1=选花色, 2=选点数, 3=激活贴图)
```

### 多线程架构

```
┌─────────────────────────────────────┐
│         Main Thread (UI)             │
│  - UI 渲染                           │
│  - 用户交互处理                      │
└────────────┬────────────────────────┘
             │
    ┌────────┴────────┐
    │                 │
┌───▼──────┐    ┌────▼────────────┐
│ Processing    │ CameraExecutor   │
│ Thread        │                  │
│ (HandlerThread)│ (Executors)     │
│               │                  │
│ - OpenCV 处理 │ - 相机帧捕获    │
│ - Mat 变换    │ - ImageProxy    │
└────────────┘ └─────────────────┘
```

**三层执行模型**：
1. **CameraExecutor** → ImageAnalysis 获取相机帧
2. **ProcessingHandler** → 后台线程执行 OpenCV 算法
3. **MainThread** → UI 线程更新预览和参数

---

## 🔍 图像处理管线

### 完整流程

```
ImageProxy (NV21)
    ↓
imageProxyToNv21() ─── YUV420 格式转 NV21 字节数组
    ↓
normalizeFrame()  ─── 颜色空间转换 + 旋转 + 镜像
    ├─ CVTColor: YUV2RGB_NV21
    ├─ Core.rotate(ROTATE_90_CLOCKWISE)
    └─ Core.flip (前置摄像头)
    ↓
detectObjectContours()
    ├─ CVTColor: RGB2GRAY
    ├─ GaussianBlur
    ├─ Canny
    ├─ MorphologyEx (MORPH_CLOSE)
    ├─ FindContours
    └─ 卡牌识别与选择
    ↓
replaceDetectedCard()
    ├─ Perspective Transform
    ├─ WarpPerspective
    └─ Mask Copy
    ↓
Mat → Bitmap → ImageView
```

### 关键函数详解

#### 1. `imageProxyToNv21(imageProxy: ImageProxy): ByteArray`

**目的**：将 ImageProxy 转换为 NV21 格式字节数组

```kotlin
private fun imageProxyToNv21(image: ImageProxy): ByteArray {
    val yBuffer = image.planes[0].buffer      // Y plane
    val uBuffer = image.planes[1].buffer      // U plane
    val vBuffer = image.planes[2].buffer      // V plane
    
    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()
    
    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)           // V 先
    uBuffer.get(nv21, ySize + vSize, uSize)   // U 后 (NV21 = NV12 + V/U 交换)
    return nv21
}
```

**关键点**：
- NV21 格式：Y plane + (V, U 交错)
- 必须按照 V, U 顺序放置，不是 U, V

#### 2. `normalizeFrame(data: ByteArray): Mat`

**目的**：标准化帧（颜色转换、旋转、镜像）

```kotlin
private fun normalizeFrame(data: ByteArray): Mat {
    val yuv = Mat(previewHeight + previewHeight / 2, previewWidth, CvType.CV_8UC1)
    yuv.put(0, 0, data)
    
    val rgb = Mat()
    Imgproc.cvtColor(yuv, rgb, Imgproc.COLOR_YUV2RGB_NV21)  // NV21 → RGB
    yuv.release()
    
    val rotated = Mat()
    Core.rotate(rgb, rotated, Core.ROTATE_90_CLOCKWISE)     // 旋转 90°
    rgb.release()
    
    if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
        Core.flip(rotated, rotated, 0)                       // 前置镜像
    }
    return rotated
}
```

**关键点**：
- CameraX 提供的帧默认是 90° 旋转的
- 前置摄像头需要镜像（flip axis 0 = 竖直翻转）

#### 3. `detectObjectContours(data: ByteArray)` — 核心检测逻辑

**流程**：

```kotlin
// 1. 颜色转换
Imgproc.cvtColor(frame, gray, Imgproc.COLOR_RGB2GRAY)

// 2. 高斯模糊（去噪）
Imgproc.GaussianBlur(gray, blurred, Size(blurSize, blurSize), 0.0)

// 3. Canny 边界检测
Imgproc.Canny(blurred, edges, cannyLower, cannyUpper)

// 4. 形态学闭运算（补缝）
val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(morphKernelSize, morphKernelSize))
Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)

// 5. 轮廓提取
val contours = mutableListOf<MatOfPoint>()
Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

// 6. 卡牌识别（选择最佳轮廓）
var bestContour: MatOfPoint? = selectBestContour(contours)

// 7. 角点提取
val (tl, tr, br, bl) = extractCorners(bestContour)

// 8. 替换卡牌（如果状态为 3）
if (magicState == 3) {
    replaceDetectedCard(frame, tl, tr, br, bl)
}
```

### 卡牌识别算法

#### 前置摄像头策略（中心优先）

```kotlin
if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
    val dist = hypot(minRect.center.x - frameCenterX, minRect.center.y - frameCenterY)
    if (dist < minCenterDist) {
        minCenterDist = dist
        bestContour = contour
    }
}
```

**原理**：前置摄像头通常只能看到一张卡牌，选择离画面中心最近的轮廓。

#### 后置摄像头策略（形状+面积筛选）

```kotlin
if (lensFacing == CameraSelector.LENS_FACING_BACK) {
    val w = minRect.size.width
    val h = minRect.size.height
    
    val ratio = w.coerceAtLeast(h) / w.coerceAtMost(h)        // 宽高比
    val rectArea = w * h
    val extent = area / rectArea                              // 面积填充度
    
    // 条件：宽高比 1.3~1.9（卡牌比例）+ 填充度 > 73%
    if (ratio in 1.3..1.9 && extent > 0.73) {
        if (area > maxArea) {
            maxArea = area
            bestContour = contour
        }
    }
}
```

**原理**：后置摄像头可能看到多张卡牌，需要形状和面积双重筛选。

### 角点提取算法

#### 前置摄像头（使用和与差）

```kotlin
var tl = points[0]; var tr = points[0]; var bl = points[0]; var br = points[0]
var minSum = Double.MAX_VALUE; var maxDiff = -Double.MAX_VALUE; var minDiff = Double.MAX_VALUE

for (p in points) {
    val sum = p.x + p.y      // x + y：左上最小，右下最大
    val diff = p.x - p.y     // x - y：右上最大，左下最小
    
    if (sum < minSum) { minSum = sum; tl = p }      // 左上
    if (diff > maxDiff) { maxDiff = diff; tr = p }  // 右上
    if (diff < minDiff) { minDiff = diff; bl = p }  // 左下
}
br = Point(tr.x + bl.x - tl.x, tr.y + bl.y - tl.y) // 由对角线推导
```

**原理**：
- $tl$: $x + y$ 最小
- $tr$: $x - y$ 最大
- $bl$: $x - y$ 最小
- $br$: 平行四边形性质推导

#### 后置摄像头（标准四点排序）

```kotlin
var tl = points[0]; var tr = points[0]; var br = points[0]; var bl = points[0]
var minSum = Double.MAX_VALUE; var maxSum = -Double.MAX_VALUE
var maxDiff = -Double.MAX_VALUE; var minDiff = Double.MAX_VALUE

for (p in points) {
    val sum = p.x + p.y
    val diff = p.x - p.y
    
    if (sum < minSum) { minSum = sum; tl = p }      // 左上
    if (sum > maxSum) { maxSum = sum; br = p }      // 右下
    if (diff > maxDiff) { maxDiff = diff; tr = p }  // 右上
    if (diff < minDiff) { minDiff = diff; bl = p }  // 左下
}
```

### 透视变换和替换

```kotlin
private fun replaceDetectedCard(frame: Mat, tl: Point, tr: Point, br: Point, bl: Point) {
    val srcMat = replacementCardMat!!
    val w = srcMat.cols().toDouble()
    val h = srcMat.rows().toDouble()
    
    // 源图片的四个角
    val srcPts = MatOfPoint2f(
        Point(0.0, 0.0), Point(w, 0.0), Point(w, h), Point(0.0, h)
    )
    
    // 目标图片的四个角（检测到的卡牌位置）
    val dstPts = MatOfPoint2f(tl, tr, br, bl)
    
    // 计算透视变换矩阵
    val transformMatrix = Imgproc.getPerspectiveTransform(srcPts, dstPts)
    
    // 应用变换
    val warpedCard = Mat()
    Imgproc.warpPerspective(srcMat, warpedCard, transformMatrix, frame.size())
    
    // 创建遮罩（只显示卡牌区域）
    val mask = Mat.zeros(frame.size(), CvType.CV_8UC1)
    val maskPolygon = listOf(MatOfPoint(tl, tr, br, bl))
    Imgproc.fillPoly(mask, maskPolygon, Scalar(255.0))
    
    // 合成到原图
    warpedCard.copyTo(frame, mask)
    
    // 清理
    warpedCard.release()
    mask.release()
    transformMatrix.release()
}
```

---

## 🎮 魔术状态机

### 状态转移图

```
      ┌──────────┐
      │   状态0   │ ◄──────┐
      │   待机     │        │
      │  不贴图    │        │
      └────┬─────┘        │
           │ 短按快门      │
           ▼              │
      ┌──────────┐        │
      │   状态1   │        │
      │  选花色   │        │
      │ 2×2网格   │        │
      └────┬─────┘        │
           │ 触摸选花色    │
           ▼              │
      ┌──────────┐        │
      │   状态2   │        │
      │  选点数   │        │
      │ 3×5网格   │        │
      └────┬─────┘        │
           │ 触摸选点数    │
           ▼              │
      ┌──────────┐        │
      │   状态3   │        │
      │ 激活贴图  │        │
      │ 实时替换  │        │
      └────┬─────┘        │
           │              │
           └──────────────┘
```

### 状态定义

```kotlin
private var magicState = 0
// 0 = 待机 (不贴图)
// 1 = 选花色中
// 2 = 选点数中
// 3 = 激活贴图

private var selectedSuit = -1    // 0=黑桃, 1=红心, 2=梅花, 3=方块
private var selectedRank = -1    // 1~12 = A~12, 13 = K
```

### 卡牌 ID 计算

```
finalCardId = selectedSuit * 13 + selectedRank

范围：0~51（52 张牌）
黑桃：0~12    (A~K)
红心：13~25   (A~K)
梅花：26~38   (A~K)
方块：39~51   (A~K)
```

---

## 💾 参数持久化

### SharedPreferences 设计

```kotlin
private fun getPrefPrefix(): String = 
    if (lensFacing == CameraSelector.LENS_FACING_FRONT) "front_" else "back_"

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
```

**存储键**：
- `front_blurSize`, `front_cannyLower`, `front_cannyUpper`, `front_morphKernelSize`
- `back_blurSize`, `back_cannyLower`, `back_cannyUpper`, `back_morphKernelSize`

---

## 🔧 防闪烁机制

### 缓存机制

```kotlin
private var lastTl: Point? = null
private var lastTr: Point? = null
private var lastBl: Point? = null
private var lastBr: Point? = null
private var missedFrames = 1
private val MAX_MISSED_FRAMES = 3

// 检测失败时的处理
if (bestContour != null) {
    lastTl = tl; lastTr = tr; lastBl = bl; lastBr = br
    missedFrames = 0
} else {
    if (lastTl != null && missedFrames < MAX_MISSED_FRAMES) {
        missedFrames++
        if (magicState == 3) replaceDetectedCard(frame, lastTl!!, lastTr!!, lastBr!!, lastBl!!)
    } else {
        clearCache()
    }
}
```

**原理**：
- 连续检测失败最多 3 帧
- 使用最后一次成功的卡牌位置继续替换
- 保证 AR 贴图的连贯性

---

## 📦 项目结构

```
magicCamera/
├── app/
│   ├── src/main/
│   │   ├── java/com/yuer/magicCamera/
│   │   │   └── MainActivity.kt              # 主应用文件（完整实现）
│   │   ├── res/
│   │   │   ├── drawable/
│   │   │   │   ├── card_0.png ~ card_51.png # 52 张卡牌图片
│   │   │   │   └── ...
│   │   │   ├── layout/
│   │   │   │   └── activity_main.xml       # UI 布局
│   │   │   └── values/
│   │   │       └── strings.xml             # 字符串资源
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts                    # App 模块配置
│   └── proguard-rules.pro
├── gradle/
│   └── libs.versions.toml                  # 依赖版本管理
├── build.gradle.kts                        # 根项目配置
├── settings.gradle.kts
├── gradlew / gradlew.bat
├── gradle.properties
├── README.md                               # 普通用户文档
├── DEVELOPER.md                            # 本文件
└── local.properties
```
