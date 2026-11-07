package com.procam.ai

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

data class QuickMenuTile(
    val title: String,
    val subtitle: String,
    val isActive: Boolean,
    val action: () -> Unit
)

fun triggerPocoHaptics(context: Context, type: Int = 1) {
    try {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val effect = when (type) {
                1 -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                2 -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                else -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            }
            vibrator?.vibrate(effect)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(35L, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(35L)
        }
    } catch (_: Exception) {}
}

class MainActivity : ComponentActivity() {
    var onVolumeTrigger: (() -> Unit)? = null

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            onVolumeTrigger?.invoke()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                PureCinemaCameraApp(
                    registerVolumeTrigger = { action -> onVolumeTrigger = action }
                )
            }
        }
    }
}

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
@Composable
fun PureCinemaCameraApp(registerVolumeTrigger: (() -> Unit) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // 🔄 Auto-Rotate सेन्सर
    var deviceOrientation by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val newRot = when (orientation) {
                    in 45..134 -> 90
                    in 135..224 -> 180
                    in 225..314 -> 270
                    else -> 0
                }
                if (newRot != deviceOrientation) {
                    deviceOrientation = newRot
                }
            }
        }
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        onDispose { orientationListener.disable() }
    }

    val uiRotation by animateFloatAsState(
        targetValue = when (deviceOrientation) {
            90 -> 90f
            270 -> -90f
            180 -> 180f
            else -> 0f
        },
        label = "uiRot"
    )

    // 🔄 Accelerometer Level Line
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager }
    var phoneTiltAngle by remember { mutableFloatStateOf(0f) }
    var wasLevelAnnounced by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val accel = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent?) {
                event?.let {
                    val x = it.values[0]
                    val y = it.values[1]
                    val angle = when (deviceOrientation) {
                        90 -> Math.toDegrees(kotlin.math.atan2(y.toDouble(), x.toDouble())).toFloat() - 90f
                        270 -> Math.toDegrees(kotlin.math.atan2(y.toDouble(), -x.toDouble())).toFloat() - 90f
                        180 -> Math.toDegrees(kotlin.math.atan2(x.toDouble(), -y.toDouble())).toFloat()
                        else -> Math.toDegrees(kotlin.math.atan2(-x.toDouble(), y.toDouble())).toFloat()
                    }
                    phoneTiltAngle = angle
                    val isLevel = abs(angle) < 1.2f
                    if (isLevel && !wasLevelAnnounced) {
                        triggerPocoHaptics(context, 2)
                        wasLevelAnnounced = true
                    } else if (!isLevel) {
                        wasLevelAnnounced = false
                    }
                }
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        sensorManager?.registerListener(listener, accel, android.hardware.SensorManager.SENSOR_DELAY_UI)
        onDispose { sensorManager?.unregisterListener(listener) }
    }

    var lastCapturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showInAppPhotoViewer by remember { mutableStateOf(false) }

    val prefs = remember { context.getSharedPreferences("procam_ai_prefs", Context.MODE_PRIVATE) }
    var apiToken by remember { mutableStateOf(prefs.getString("replicate_token", "") ?: "") }
    var showTokenDialog by remember { mutableStateOf(false) }
    var tokenInputText by remember { mutableStateOf(apiToken) }

    var isAiRestoring by remember { mutableStateOf(false) }
    var aiRestoredBmp by remember { mutableStateOf<Bitmap?>(null) }
    var showAiResultDialog by remember { mutableStateOf(false) }

    val aiPhotoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
                val stream = context.contentResolver.openInputStream(it)
                val loaded = BitmapFactory.decodeStream(stream)
                if (loaded != null) {
                    if (apiToken.isBlank()) {
                        Toast.makeText(context, "कृपया आधी 🔑 AI Token पेस्ट करा!", Toast.LENGTH_LONG).show()
                        showTokenDialog = true
                    } else {
                        isAiRestoring = true
                        aiRestoredBmp = null
                        AiRestoreEngine.restorePhotoWithGenerativeAI(context, loaded, apiToken) { restored ->
                            isAiRestoring = false
                            if (restored != null) {
                                aiRestoredBmp = restored
                                showAiResultDialog = true
                                saveFinalProcessedBitmap(context, restored, "AI_4K_RESTORED")
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingTimer by remember { mutableIntStateOf(0) }

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var isFlashOn by remember { mutableStateOf(false) }
    var currentZoom by remember { mutableFloatStateOf(1f) }
    var isZoomDialVisible by remember { mutableStateOf(false) }

    var selectedMode by remember { mutableStateOf("PHOTO") }
    var activeFilter by remember { mutableStateOf("Normal") }

    // 💎 ३ नवीन Quality Modes (Natural, Crystal, Ultra)
    var qualityMode by remember { mutableStateOf("Crystal") }

    var isFilterSidebarOpen by remember { mutableStateOf(false) }
    var isColorReadingOpen by remember { mutableStateOf(false) }

    var tempValue by remember { mutableFloatStateOf(0f) }
    var tintValue by remember { mutableFloatStateOf(0f) }
    var satValue by remember { mutableFloatStateOf(0f) }
    var redValue by remember { mutableFloatStateOf(128f) }
    var greenValue by remember { mutableFloatStateOf(128f) }
    var blueValue by remember { mutableFloatStateOf(128f) }

    var selectedVideoResolution by remember { mutableStateOf("4K") }
    var isActionModeOn by remember { mutableStateOf(false) }

    var isQuickMenuOpen by remember { mutableStateOf(false) }
    var selectedAspect by remember { mutableStateOf("Full") }
    var timerDuration by remember { mutableIntStateOf(0) }
    var isGridOn by remember { mutableStateOf(true) }
    var isLevelOn by remember { mutableStateOf(true) }
    var isWatermarkOn by remember { mutableStateOf(true) }
    var isHDROn by remember { mutableStateOf(true) }
    var isAICameraOn by remember { mutableStateOf(true) }

    var selectedProTab by remember { mutableStateOf("ISO") }
    var manualIso by remember { mutableFloatStateOf(100f) }
    var isIsoAuto by remember { mutableStateOf(true) }
    var shutterDenom by remember { mutableFloatStateOf(120f) }
    var isShutterAuto by remember { mutableStateOf(true) }
    var evValue by remember { mutableFloatStateOf(0f) }
    var exposureComp by remember { mutableFloatStateOf(0f) }
    var wbKelvin by remember { mutableFloatStateOf(5500f) }
    var isWbAuto by remember { mutableStateOf(true) }
    var manualFocusDistance by remember { mutableFloatStateOf(0f) }
    var isFocusAuto by remember { mutableStateOf(true) }

    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var portraitBlur by remember { mutableFloatStateOf(0.70f) }
    var countdownValue by remember { mutableIntStateOf(0) }
    var isCountingDown by remember { mutableStateOf(false) }

    val previewView = remember { PreviewView(context) }

    var hasPermissions by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        hasPermissions = perms[Manifest.permission.CAMERA] == true
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingTimer = 0
            while (isRecording) {
                delay(1000)
                recordingTimer++
            }
        }
    }

    fun applySafeZoom(targetZoom: Float) {
        val zoomState = camera?.cameraInfo?.zoomState?.value
        val minZ = zoomState?.minZoomRatio ?: 0.6f
        val maxZ = (zoomState?.maxZoomRatio ?: 10f).coerceAtMost(10f)
        val safe = targetZoom.coerceIn(minZ, maxZ)
        currentZoom = safe
        camera?.cameraControl?.setZoomRatio(safe)
    }

    fun applyProManualSettings() {
        val cam = camera ?: return
        try {
            val camera2Control = Camera2CameraControl.from(cam.cameraControl)
            val builder = CaptureRequestOptions.Builder()

            if (!isIsoAuto && !isShutterAuto) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, manualIso.toInt())
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, (1_000_000_000L / shutterDenom).toLong())
            } else if (!isIsoAuto) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, manualIso.toInt())
            } else {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                cam.cameraControl.setExposureCompensationIndex(evValue.toInt())
            }

            if (!isWbAuto) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            } else {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }

            if (!isFocusAuto) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance)
            } else {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }

            camera2Control.setCaptureRequestOptions(builder.build())
        } catch (_: Exception) {}
    }

    LaunchedEffect(hasPermissions, lensFacing, selectedMode, selectedVideoResolution, isActionModeOn, activeFilter, tempValue, tintValue, isFlashOn) {
        if (hasPermissions) {
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            cameraProvider.unbindAll()

            try {
                if (selectedMode == "VIDEO") {
                    val targetQuality = when (selectedVideoResolution) {
                        "4K" -> Quality.UHD
                        "1080p" -> Quality.FHD
                        "720p" -> Quality.HD
                        else -> Quality.FHD
                    }
                    val qualitySelector = QualitySelector.from(targetQuality, FallbackStrategy.lowerQualityOrHigherThan(targetQuality))
                    val recorder = Recorder.Builder().setQualitySelector(qualitySelector).build()
                    val vc = VideoCapture.withOutput(recorder)
                    videoCapture = vc
                    camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, vc)
                } else {
                    val ic = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                    imageCapture = ic
                    camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, ic)
                    if (selectedMode == "NIGHT" || activeFilter.contains("Astro") || activeFilter.contains("Night")) {
                        camera?.cameraControl?.setExposureCompensationIndex(2)
                    } else if (selectedMode != "PRO") {
                        val ev = if (activeFilter.contains("Cinematic")) -1 else evValue.toInt()
                        camera?.cameraControl?.setExposureCompensationIndex(ev)
                    }
                }

                applySafeZoom(currentZoom)
                camera?.cameraControl?.enableTorch(isFlashOn)

                try {
                    val camera2Control = Camera2CameraControl.from(camera!!.cameraControl)
                    val builder = CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                        .setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                        .setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                        .setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)

                    when {
                        activeFilter == "🥽 NVG Green" -> {
                            builder.setCaptureRequestOption(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
                            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                        }
                        activeFilter == "Noir" -> {
                            builder.setCaptureRequestOption(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_MONO)
                        }
                        activeFilter.contains("Retro") -> {
                            builder.setCaptureRequestOption(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_SEPIA)
                        }
                        activeFilter.contains("Cinematic") || activeFilter == "Warm" || activeFilter.contains("Lens Blur") -> {
                            builder.setCaptureRequestOption(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
                            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT)
                        }
                        activeFilter.contains("Cyberpunk") || activeFilter == "Cool" -> {
                            builder.setCaptureRequestOption(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
                            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT)
                        }
                        else -> {
                            builder.setCaptureRequestOption(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
                            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        }
                    }

                    if (tempValue > 20f) {
                        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT)
                    } else if (tempValue < -20f) {
                        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT)
                    }

                    camera2Control.setCaptureRequestOptions(builder.build())
                } catch (_: Exception) {}

                if (selectedMode == "PRO") {
                    applyProManualSettings()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "कॅमेरा त्रुटी: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(manualIso, isIsoAuto, shutterDenom, isShutterAuto, evValue, isWbAuto, manualFocusDistance, isFocusAuto) {
        if (selectedMode == "PRO") {
            applyProManualSettings()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermissions) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoomChange, _ ->
                            applySafeZoom(currentZoom * zoomChange)
                        }
                    }
            )
        }

        // 🎯 Tap-to-Focus Touch Layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        focusPoint = offset
                        val factory = previewView.meteringPointFactory
                        val point = factory.createPoint(offset.x, offset.y)
                        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                        scope.launch {
                            delay(4000)
                            focusPoint = null
                        }
                    }
                }
        )

        // 🎬 2.39:1 Cinema Letterbox Bars
        if (activeFilter.contains("Cinematic")) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val h = maxHeight
                val barHeight = h * 0.12f
                Box(modifier = Modifier.fillMaxWidth().height(barHeight).align(Alignment.TopCenter).background(Color.Black))
                Box(modifier = Modifier.fillMaxWidth().height(barHeight).align(Alignment.BottomCenter).background(Color.Black))
            }
        }

        // 3x3 Grid
        if (isGridOn) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height; val c = Color(0x33FFFFFF)
                drawLine(c, Offset(w / 3, 0f), Offset(w / 3, h), 1.dp.toPx())
                drawLine(c, Offset(w * 2 / 3, 0f), Offset(w * 2 / 3, h), 1.dp.toPx())
                drawLine(c, Offset(0f, h / 3), Offset(w, h / 3), 1.dp.toPx())
                drawLine(c, Offset(0f, h * 2 / 3), Offset(w, h * 2 / 3), 1.dp.toPx())
            }
        }

        // 🔄 हार्डवेअर Accelerometer शी जोडलेली खरी लेव्हल लाईन
        if (isLevelOn) {
            val isPerfectLevel = abs(phoneTiltAngle) < 1.2f
            val lineColor = if (isPerfectLevel) Color(0xFF34C759) else Color(0xFFFFCC00)

            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val midX = w / 2f
                val midY = h / 2f

                drawLine(Color(0x55FFFFFF), Offset(midX - 70.dp.toPx(), midY), Offset(midX - 30.dp.toPx(), midY), 1.5.dp.toPx())
                drawLine(Color(0x55FFFFFF), Offset(midX + 30.dp.toPx(), midY), Offset(midX + 70.dp.toPx(), midY), 1.5.dp.toPx())

                val angleRad: Double = Math.toRadians((-phoneTiltAngle).toDouble())
                val cosVal: Float = Math.cos(angleRad).toFloat()
                val sinVal: Float = Math.sin(angleRad).toFloat()
                val lineLength: Float = 85.dp.toPx()
                val xOff: Float = lineLength * cosVal
                val yOff: Float = lineLength * sinVal

                drawLine(lineColor, Offset(midX - xOff, midY - yOff), Offset(midX + xOff, midY + yOff), 3.dp.toPx())
                drawCircle(lineColor, radius = 3.5.dp.toPx(), center = Offset(midX, midY))
            }
        }

        // ☀️ बोटाखाली अचूक Tap-to-Focus चौकोन आणि सूर्य स्लायडर
        focusPoint?.let { pos ->
            val xDp = with(density) { pos.x.toDp() } - 35.dp
            val yDp = with(density) { pos.y.toDp() } - 35.dp
            Row(
                modifier = Modifier.offset(xDp, yDp).rotate(uiRotation),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(70.dp)
                        .border(2.dp, Color(0xFFFFCC00), RoundedCornerShape(6.dp))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0x55000000), CircleShape)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dragAmount ->
                                val delta = -dragAmount / 20f
                                evValue = (evValue + delta).coerceIn(-4f, 4f)
                                exposureComp = evValue
                                camera?.cameraControl?.setExposureCompensationIndex(evValue.toInt())
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("☀️", fontSize = 18.sp)
                }
            }
        }

        // Live Histogram Box
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 135.dp)
                .rotate(uiRotation)
                .size(62.dp, 36.dp)
                .background(Color(0x88000000), RoundedCornerShape(6.dp))
                .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("HISTO ∿", color = Color(0xFFFFCC00), fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }

        if (isRecording) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
                    .rotate(uiRotation)
                    .background(Color(0x88000000), RoundedCornerShape(8.dp))
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                listOf(Color.Red, Color.Yellow, Color.Green, Color.Green, Color.Green).forEach { col ->
                    Box(modifier = Modifier.size(16.dp, 4.dp).background(col, RoundedCornerShape(1.dp)))
                }
            }
        }
// -------------------------------------------------------------
        // 🔥 उजव्या बाजूला फ्लोटिंग बाण आणि RGB डॉट
        // -------------------------------------------------------------
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clickable { isFilterSidebarOpen = !isFilterSidebarOpen }
                    .rotate(uiRotation)
                    .background(Color(0x66FFFFFF), CircleShape)
                    .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isFilterSidebarOpen) "▶" else "◀",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }

            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clickable { isColorReadingOpen = !isColorReadingOpen }
                    .rotate(uiRotation)
                    .background(Color(0x66FFFFFF), CircleShape)
                    .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(
                            Brush.sweepGradient(
                                listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
                            ),
                            CircleShape
                        )
                )
            }
        }

        // 🎨 COLOR READING OPTION विंडो
        AnimatedVisibility(
            visible = isColorReadingOpen,
            enter = fadeIn() + slideInHorizontally { it },
            exit = fadeOut() + slideOutHorizontally { it },
            modifier = Modifier
                .align(Alignment.Center)
                .padding(16.dp)
                .rotate(uiRotation)
        ) {
            val currentColor = Color(
                ((redValue + tempValue * 0.5f + tintValue * 0.3f).coerceIn(0f, 255f)).toInt(),
                ((greenValue - tintValue * 0.5f).coerceIn(0f, 255f)).toInt(),
                ((blueValue - tempValue * 0.5f + tintValue * 0.3f).coerceIn(0f, 255f)).toInt()
            )
            val hexCode = String.format("#%02X%02X%02X", currentColor.red.toInt() * 255, currentColor.green.toInt() * 255, currentColor.blue.toInt() * 255)

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xF2161A22)),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2C3240)),
                modifier = Modifier.width(310.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("LIVE COLOR GRADING", color = Color.White, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text("✕", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.clickable { isColorReadingOpen = false }.padding(4.dp))
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(95.dp)
                            .background(Color(0xFF0F1218), RoundedCornerShape(10.dp))
                            .border(1.dp, Color(0xFF242A36), RoundedCornerShape(10.dp))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val w = size.width
                                val h = size.height
                                drawLine(Color(0xFF0A84FF), Offset(w * 0.15f, h * 0.9f), Offset(w * 0.25f, h * 0.15f), 3.dp.toPx())
                                drawLine(Color(0xFF0A84FF), Offset(w * 0.25f, h * 0.15f), Offset(w * 0.45f, h * 0.8f), 3.dp.toPx())
                                drawLine(Color(0xFFFF3B30), Offset(w * 0.35f, h * 0.85f), Offset(w * 0.50f, h * 0.35f), 3.dp.toPx())
                                drawLine(Color(0xFFFF3B30), Offset(w * 0.50f, h * 0.35f), Offset(w * 0.85f, h * 0.9f), 3.dp.toPx())
                                drawLine(Color(0xFFFFD60A), Offset(w * 0.55f, h * 0.75f), Offset(w * 0.70f, h * 0.60f), 2.dp.toPx())
                            }
                            Text(hexCode, color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopEnd).padding(2.dp))
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Box(
                            modifier = Modifier
                                .width(60.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(8.dp))
                                .background(currentColor)
                                .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Temp:", color = Color.White, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.width(42.dp))
                        Slider(
                            value = tempValue,
                            onValueChange = { tempValue = it },
                            valueRange = -100f..100f,
                            colors = SliderDefaults.colors(thumbColor = Color(0xFFFFCC00), activeTrackColor = Color(0xFFFF9500), inactiveTrackColor = Color(0xFF0A84FF)),
                            modifier = Modifier.weight(1f)
                        )
                        Text("${tempValue.toInt()}", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(26.dp))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Tint:", color = Color.White, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.width(42.dp))
                        Slider(
                            value = tintValue,
                            onValueChange = { tintValue = it },
                            valueRange = -100f..100f,
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFFAF52DE), inactiveTrackColor = Color(0xFF34C759)),
                            modifier = Modifier.weight(1f)
                        )
                        Text("${tintValue.toInt()}", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(26.dp))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Sat:", color = Color.White, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.width(42.dp))
                        Slider(
                            value = satValue,
                            onValueChange = { satValue = it },
                            valueRange = -100f..100f,
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFFFF2D55), inactiveTrackColor = Color.Gray),
                            modifier = Modifier.weight(1f)
                        )
                        Text("${satValue.toInt()}", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(26.dp))
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Reset All", color = Color(0xFFFFCC00), fontSize = 11.sp, modifier = Modifier.clickable {
                            tempValue = 0f; tintValue = 0f; satValue = 0f
                        }.padding(4.dp))
                        Text("Real-Time Engine", color = Color.Gray, fontSize = 10.sp)
                    }
                }
            }
        }

        // 🎬 वर उचललेला फिल्टर्स साइडबार
        AnimatedVisibility(
            visible = isFilterSidebarOpen,
            enter = fadeIn() + slideInHorizontally { it },
            exit = fadeOut() + slideOutHorizontally { it },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 75.dp, top = 70.dp)
                .fillMaxHeight(0.50f)
                .width(185.dp)
                .rotate(uiRotation)
        ) {
            val allFiltersList = listOf(
                "Normal" to "🚫",
                "💧 Lens Blur" to "💧",
                "Cinematic Look (9-in-1)" to "🎬",
                "🥽 NVG Green" to "🥽",
                "🌙 Color Night" to "🌙",
                "Video Glow" to "✨",
                "Retro Look" to "🎞️",
                "Urban Night" to "🌃",
                "4K Quality" to "⚡",
                "Standard" to "🍎",
                "Rich Contrast" to "🎭",
                "Vibrant" to "🌈",
                "Warm" to "☀️",
                "Cool" to "❄️",
                "Noir" to "🖤",
                "Astro" to "🌌",
                "Macro" to "🔬"
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xF2141822)),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2C3240)),
                modifier = Modifier.width(185.dp).fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("FILTERS (UP-DOWN)", color = Color.Gray, fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(4.dp))
                    allFiltersList.forEach { (name, icon) ->
                        val isSel = activeFilter == name
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isSel) Color(0xFFFFCC00) else Color(0xFF1E2433), RoundedCornerShape(10.dp))
                                .border(1.dp, if (isSel) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
                                .clickable {
                                    activeFilter = name
                                    isFilterSidebarOpen = false
                                }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(icon, fontSize = 13.sp)
                            Text(
                                text = name,
                                color = if (isSel) Color.Black else Color.White,
                                fontSize = 11.sp,
                                fontWeight = if (isSel) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // 🔥 टॉप बार + ३ Quality Modes (Natural, Crystal, Ultra)
        // -------------------------------------------------------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        isFlashOn = !isFlashOn
                        triggerPocoHaptics(context, 1)
                    },
                    modifier = Modifier.size(34.dp).rotate(uiRotation).background(if (isFlashOn) Color(0xFFFFCC00) else Color(0x66000000), CircleShape)
                ) {
                    Text(if (isFlashOn) "⚡" else "⚡", color = if (isFlashOn) Color.Black else Color.White)
                }

                // 💎 ३ नवीन Quality Modes Switcher (Natural, Crystal, Ultra)
                Row(
                    modifier = Modifier
                        .rotate(uiRotation)
                        .background(Color(0x88000000), RoundedCornerShape(20.dp))
                        .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(20.dp))
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("Natural" to "🌿", "Crystal" to "💎", "Ultra" to "⚡").forEach { (qMode, icon) ->
                        val isSel = qualityMode == qMode
                        Box(
                            modifier = Modifier
                                .background(if (isSel) Color(0xFFFFCC00) else Color.Transparent, RoundedCornerShape(16.dp))
                                .clickable {
                                    qualityMode = qMode
                                    triggerPocoHaptics(context, 1)
                                    Toast.makeText(context, "$icon $qMode Mode", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$icon $qMode",
                                color = if (isSel) Color.Black else Color.White,
                                fontSize = 10.sp,
                                fontWeight = if (isSel) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .rotate(uiRotation)
                        .background(Color(0xDDFFCC00), RoundedCornerShape(14.dp))
                        .clickable { aiPhotoPickerLauncher.launch("image/*") }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("⚡ AI 4K", color = Color.Black, fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }

                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .rotate(uiRotation)
                        .background(Color(0x66000000), CircleShape)
                        .clickable { isQuickMenuOpen = !isQuickMenuOpen },
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (isQuickMenuOpen) "▲" else "▼", color = Color.White, fontSize = 12.sp)
                }
            }
        }

        // Quick Settings Dropdown
        AnimatedVisibility(
            visible = isQuickMenuOpen,
            modifier = Modifier.padding(top = 80.dp, start = 16.dp, end = 16.dp).align(Alignment.TopCenter).rotate(uiRotation)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xF2141822)),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2C3240))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    val tileRows = listOf(
                        listOf(
                            QuickMenuTile("HDR", if (isHDROn) "On" else "Off", isHDROn) { isHDROn = !isHDROn },
                            QuickMenuTile("AI Cam", if (isAICameraOn) "On" else "Off", isAICameraOn) { isAICameraOn = !isAICameraOn },
                            QuickMenuTile("Aspect", selectedAspect, true) {
                                selectedAspect = when(selectedAspect) { "Full" -> "4:3"; "4:3" -> "16:9"; "16:9" -> "1:1"; else -> "Full" }
                            },
                            QuickMenuTile("Timer", if (timerDuration == 0) "Off" else "${timerDuration}s", timerDuration > 0) {
                                timerDuration = when(timerDuration) { 0 -> 3; 3 -> 5; 5 -> 10; else -> 0 }
                            }
                        ),
                        listOf(
                            QuickMenuTile("Grid #", if (isGridOn) "On" else "Off", isGridOn) { isGridOn = !isGridOn },
                            QuickMenuTile("Level ⊝", if (isLevelOn) "On" else "Off", isLevelOn) { isLevelOn = !isLevelOn },
                            QuickMenuTile("Watermark", if (isWatermarkOn) "On" else "Off", isWatermarkOn) { isWatermarkOn = !isWatermarkOn },
                            QuickMenuTile("🔑 AI Token", if (apiToken.isNotBlank()) "Active" else "Set Token", apiToken.isNotBlank()) {
                                showTokenDialog = true
                            }
                        )
                    )

                    tileRows.forEach { rowTiles ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            rowTiles.forEach { tile ->
                                Column(
                                    modifier = Modifier
                                        .size(72.dp, 58.dp)
                                        .background(if (tile.isActive) Color(0x33FFCC00) else Color(0xFF1E2433), RoundedCornerShape(10.dp))
                                        .border(1.dp, if (tile.isActive) Color(0xFFFFCC00) else Color.Transparent, RoundedCornerShape(10.dp))
                                        .clickable { tile.action() }
                                        .padding(4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(tile.title, color = if (tile.isActive) Color(0xFFFFCC00) else Color.White, fontSize = 11.sp, fontWeight = and
