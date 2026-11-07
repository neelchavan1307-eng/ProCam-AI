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
                                    Text(tile.title, color = if (tile.isActive) Color(0xFFFFCC00) else Color.White, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                    Text(tile.subtitle, color = Color.Gray, fontSize = 9.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isCountingDown) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("$countdownValue", color = Color(0xFFFFCC00), fontSize = 95.sp, modifier = Modifier.rotate(uiRotation))
            }
        }

        // -------------------------------------------------------------
        // तळाचा विभाग: Pro Mode HUD + Lens Blur Slider + Arc Zoom + Shutter
        // -------------------------------------------------------------
        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (selectedMode == "PRO") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .rotate(uiRotation)
                        .background(Color(0x99111622), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ISO", color = Color.Gray, fontSize = 9.sp)
                        Text(if (isIsoAuto) "AUTO" else "${manualIso.toInt()}", color = if (selectedProTab == "ISO") Color(0xFFFFCC00) else Color.White, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("S", color = Color.Gray, fontSize = 9.sp)
                        Text(if (isShutterAuto) "AUTO" else "1/${shutterDenom.toInt()}", color = if (selectedProTab == "S") Color(0xFFFFCC00) else Color.White, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("EV", color = Color.Gray, fontSize = 9.sp)
                        Text(String.format("%+.1f", evValue), color = if (selectedProTab == "EV") Color(0xFFFFCC00) else Color.White, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("WB", color = Color.Gray, fontSize = 9.sp)
                        Text(if (isWbAuto) "AUTO" else "${wbKelvin.toInt()}K", color = if (selectedProTab == "WB") Color(0xFFFFCC00) else Color.White, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("FOCUS", color = Color.Gray, fontSize = 9.sp)
                        Text(if (isFocusAuto) "AUTO" else "MF", color = if (selectedProTab == "FOCUS") Color(0xFFFFCC00) else Color.White, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 24.dp)) {
                    when (selectedProTab) {
                        "ISO" -> Slider(value = manualIso, onValueChange = { manualIso = it; isIsoAuto = false }, valueRange = 50f..3200f, colors = SliderDefaults.colors(thumbColor = Color(0xFFFFCC00), activeTrackColor = Color(0xFFFFCC00)))
                        "S" -> Slider(value = shutterDenom, onValueChange = { shutterDenom = it; isShutterAuto = false }, valueRange = 4f..4000f, colors = SliderDefaults.colors(thumbColor = Color(0xFFFFCC00), activeTrackColor = Color(0xFFFFCC00)))
                        "EV" -> Slider(value = evValue, onValueChange = { evValue = it; exposureComp = it }, valueRange = -2f..2f, steps = 8, colors = SliderDefaults.colors(thumbColor = Color(0xFFFFCC00), activeTrackColor = Color(0xFFFFCC00)))
                        "WB" -> Slider(value = wbKelvin, onValueChange = { wbKelvin = it; isWbAuto = false }, valueRange = 2500f..7500f, colors = SliderDefaults.colors(thumbColor = Color(0xFFFFCC00), activeTrackColor = Color(0xFFFFCC00)))
                        "FOCUS" -> Slider(value = manualFocusDistance, onValueChange = { manualFocusDistance = it; isFocusAuto = false }, valueRange = 0f..10f, colors = SliderDefaults.colors(thumbColor = Color(0xFFFFCC00), activeTrackColor = Color(0xFFFFCC00)))
                    }
                }

                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("ISO", "S", "EV", "WB", "FOCUS").forEach { tab ->
                        val isSel = selectedProTab == tab
                        Button(
                            onClick = { selectedProTab = tab },
                            colors = ButtonDefaults.buttonColors(containerColor = if (isSel) Color(0xFFFFCC00) else Color(0xFF1E2433)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.rotate(uiRotation)
                        ) {
                            Text(tab, color = if (isSel) Color.Black else Color.White, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                    }
                }
            }

            if (selectedMode == "PORTRAIT" || activeFilter.contains("Lens Blur")) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("💧 ƒ 1.4 Prime • Lens Blur ${(portraitBlur * 100).toInt()}%", color = Color(0xFFFFCC00), fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.rotate(uiRotation))
                    Slider(value = portraitBlur, onValueChange = { portraitBlur = it }, valueRange = 0.2f..1.0f, colors = SliderDefaults.colors(thumbColor = Color(0xFFFFCC00), activeTrackColor = Color(0xFFFFCC00)))
                }
            }

            if (isZoomDialVisible) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 6.dp)) {
                    Text(text = String.format("%.1fx", currentZoom), color = Color(0xFFFFCC00), fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.rotate(uiRotation))
                    Spacer(modifier = Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .height(50.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val delta = dragAmount.x / 40f
                                    applySafeZoom((currentZoom + delta).coerceIn(0.6f, 10f))
                                }
                            }
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width; val h = size.height
                            drawArc(Color(0x66FFFFFF), 180f, 180f, false, Offset(0f, -h), Size(w, h * 2), style = Stroke(2.dp.toPx()))
                            listOf(0.6f, 1f, 2f, 4f, 6f, 8f, 10f).forEach { mark ->
                                val x = w * ((mark - 0.6f) / (10f - 0.6f))
                                val isNear = abs(currentZoom - mark) < 0.3f
                                drawLine(if (isNear) Color(0xFFFFCC00) else Color.White, Offset(x, h * 0.4f), Offset(x, h * 0.85f), if (isNear) 3.dp.toPx() else 1.5.dp.toPx())
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.background(Color(0x66000000), RoundedCornerShape(24.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(0.6f to ".6", 1f to "1", 2f to "2", 5f to "5", 10f to "10").forEach { (z, label) ->
                    val isSel = abs(currentZoom - z) < 0.15f
                    Box(
                        modifier = Modifier.size(32.dp).rotate(uiRotation).background(if (isSel) Color(0x44FFFFFF) else Color.Transparent, CircleShape).clickable { applySafeZoom(z); isZoomDialVisible = false },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = if (isSel) Color(0xFFFFCC00) else Color.White, fontSize = 11.sp, fontWeight = if (isSel) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal)
                    }
                }
                Box(
                    modifier = Modifier.rotate(uiRotation).background(if (isZoomDialVisible) Color(0xFFFFCC00) else Color(0x33FFFFFF), RoundedCornerShape(12.dp)).clickable { isZoomDialVisible = !isZoomDialVisible }.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("DIAL", color = if (isZoomDialVisible) Color.Black else Color.White, fontSize = 10.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val cleanModes = listOf("PHOTO", "VIDEO", "PORTRAIT", "NIGHT", "PRO")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                cleanModes.forEach { mode ->
                    val isSel = selectedMode == mode
                    Text(
                        text = mode,
                        color = if (isSel) Color(0xFFFFCC00) else Color.White,
                        fontSize = if (isSel) 14.sp else 12.sp,
                        fontWeight = if (isSel) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                        modifier = Modifier.rotate(uiRotation).clickable { selectedMode = mode }.padding(vertical = 4.dp, horizontal = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val triggerAction = {
                triggerPocoHaptics(context, 1)
                if (selectedMode == "VIDEO") {
                    if (!isRecording) {
                        videoCapture?.let {
                            activeRecording = startRealVideoRecording(context, it, activeFilter, tempValue, tintValue, satValue) { isRecording = false }
                            isRecording = true
                        }
                    } else {
                        activeRecording?.stop()
                        activeRecording = null
                        isRecording = false
                    }
                } else {
                    if (timerDuration > 0) {
                        scope.launch {
                            isCountingDown = true
                            for (i in timerDuration downTo 1) {
                                countdownValue = i
                                delay(1000)
                            }
                            isCountingDown = false
                            captureProcessedPhoto(context, imageCapture, selectedMode, activeFilter, qualityMode, portraitBlur, isWatermarkOn, selectedAspect, evValue, redValue, greenValue, blueValue, tempValue, tintValue, satValue, deviceOrientation) { capturedBmp ->
                                lastCapturedBitmap = capturedBmp
                            }
                        }
                    } else {
                        captureProcessedPhoto(context, imageCapture, selectedMode, activeFilter, qualityMode, portraitBlur, isWatermarkOn, selectedAspect, evValue, redValue, greenValue, blueValue, tempValue, tintValue, satValue, deviceOrientation) { capturedBmp ->
                            lastCapturedBitmap = capturedBmp
                        }
                    }
                }
            }

            LaunchedEffect(Unit) {
                registerVolumeTrigger { triggerAction() }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .rotate(uiRotation)
                        .background(Color(0x55000000), CircleShape)
                        .border(2.dp, if (lastCapturedBitmap != null) Color(0xFFFFCC00) else Color(0x66FFFFFF), CircleShape)
                        .clip(CircleShape)
                        .clickable {
                            if (lastCapturedBitmap != null) {
                                showInAppPhotoViewer = true
                            } else {
                                Toast.makeText(context, "अजून फोटो काढलेला नाही!", Toast.LENGTH_SHORT).show()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (lastCapturedBitmap != null) {
                        Image(
                            bitmap = lastCapturedBitmap!!.asImageBitmap(),
                            contentDescription = "Recent Shot",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text("🖼️", fontSize = 18.sp)
                    }
                }

                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(5.dp)
                        .background(if (selectedMode == "VIDEO" || isRecording) Color.Red else Color.White, CircleShape)
                        .pointerInput(selectedMode) {
                            detectTapGestures(
                                onTap = { triggerAction() },
                                onLongPress = {
                                    if (!isRecording) {
                                        triggerPocoHaptics(context, 1)
                                        videoCapture?.let {
                                            activeRecording = startRealVideoRecording(context, it, activeFilter, tempValue, tintValue, satValue) { isRecording = false }
                                            isRecording = true
                                        }
                                    }
                                }
                            )
                        }
                )

                Box(
                    modifier = Modifier.size(45.dp).rotate(uiRotation).background(Color(0x66000000), CircleShape).clickable {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Text("🔄", fontSize = 20.sp)
                }
            }
        }

        if (showInAppPhotoViewer && lastCapturedBitmap != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
                    .clickable { showInAppPhotoViewer = false },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = lastCapturedBitmap!!.asImageBitmap(),
                    contentDescription = "Full Preview",
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentScale = ContentScale.Fit
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(24.dp)
                        .size(40.dp)
                        .background(Color(0x88000000), CircleShape)
                        .clickable { showInAppPhotoViewer = false },
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", color = Color.White, fontSize = 20.sp)
                }
            }
        }

        if (isAiRestoring) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    CircularProgressIndicator(color = Color(0xFFFFCC00), modifier = Modifier.size(50.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("⚡ Real-ESRGAN 4K Super-Resolution सुरू आहे...", color = Color.White, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("क्रेन डिस्प्ले, आकडे, मजकूर व चेहरे ४ पट शार्प होत आहेत", color = Color.Gray, fontSize = 12.sp)
                }
            }
        }

        if (showAiResultDialog && aiRestoredBmp != null) {
            AlertDialog(
                onDismissRequest = { showAiResultDialog = false },
                confirmButton = {
                    Button(onClick = { showAiResultDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFCC00))) {
                        Text("पूर्ण (गॅलरीत सेव्ह झाला)", color = Color.Black)
                    }
                },
                title = { Text("🎉 4K Super-Resolution यशस्वी!", color = Color.White) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(bitmap = aiRestoredBmp!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("✅ 4K सुपर-शार्प फोटो 'Pictures/ProCam AI' मध्ये सेव्ह झाला!", color = Color(0xFF4CAF50), fontSize = 12.sp)
                    }
                },
                containerColor = Color(0xFF1E2433)
            )
        }

        if (showTokenDialog) {
            AlertDialog(
                onDismissRequest = { showTokenDialog = false },
                confirmButton = {
                    Button(
                        onClick = {
                            prefs.edit().putString("replicate_token", tokenInputText.trim()).apply()
                            apiToken = tokenInputText.trim()
                            showTokenDialog = false
                            Toast.makeText(context, "🔑 AI Token सेव्ह झाले!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFCC00))
                    ) { Text("Save Token", color = Color.Black) }
                },
                title = { Text("🔑 Replicate AI Token", color = Color.White) },
                text = {
                    Column {
                        Text("तुमचे 'r8_...' ने सुरू होणारे टोकन खाली पेस्ट करा:", color = Color.LightGray, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = tokenInputText,
                            onValueChange = { tokenInputText = it },
                            placeholder = { Text("r8_...", color = Color.Gray) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                        )
                    }
                },
                containerColor = Color(0xFF1E2433)
            )
        }
    }
}

fun startRealVideoRecording(
    context: Context,
    videoCapture: VideoCapture<Recorder>,
    activeFilter: String,
    tempVal: Float = 0f,
    tintVal: Float = 0f,
    satVal: Float = 0f,
    onComplete: () -> Unit
): Recording {
    val cleanFilterName = activeFilter.replace(" ", "_").replace("(", "").replace(")", "").replace("-", "_")
    val name = "ProCam_Video_${cleanFilterName}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}"
    
    val tempVideoFile = File(context.cacheDir, "temp_rec_${System.currentTimeMillis()}.mp4")
    val fileOutput = FileOutputOptions.Builder(tempVideoFile).build()

    val pending = videoCapture.output.prepareRecording(context, fileOutput)
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
        pending.withAudioEnabled()
    }

    return pending.start(ContextCompat.getMainExecutor(context)) { event ->
        when (event) {
            is VideoRecordEvent.Start -> {
                Toast.makeText(context, "🔴 4K [ $activeFilter ] रेकॉर्डिंग सुरू!", Toast.LENGTH_SHORT).show()
            }
            is VideoRecordEvent.Finalize -> {
                onComplete()
                if (!event.hasError()) {
                    if (activeFilter != "Normal" || tempVal != 0f || tintVal != 0f || satVal != 0f) {
                        exportCinematicVideoWithTransformer(context, Uri.fromFile(tempVideoFile), activeFilter) { success ->
                            tempVideoFile.delete()
                        }
                    } else {
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ProCam AI")
                            }
                        }
                        val outUri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                        outUri?.let { dest ->
                            context.contentResolver.openOutputStream(dest)?.use { out ->
                                tempVideoFile.inputStream().use { input -> input.copyTo(out) }
                            }
                        }
                        tempVideoFile.delete()
                        Toast.makeText(context, "🎥 4K व्हिडिओ गॅलरीत सेव्ह झाला!", Toast.LENGTH_LONG).show()
                    }
                } else {
                    tempVideoFile.delete()
                    Toast.makeText(context, "रेकॉर्डिंग त्रुटी: ${event.error}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

// 📸 3 Quality Modes (Natural, Crystal, Ultra) सह फोटो सेव्हर
fun captureProcessedPhoto(
    context: Context,
    capture: ImageCapture?,
    mode: String,
    activeFilter: String,
    qualityMode: String,
    blurLevel: Float,
    addWatermark: Boolean,
    aspectRatio: String,
    currentEV: Float,
    rVal: Float = 128f,
    gVal: Float = 128f,
    bVal: Float = 128f,
    tempVal: Float = 0f,
    tintVal: Float = 0f,
    satVal: Float = 0f,
    deviceOrientation: Int = 0,
    onSuccess: (Bitmap) -> Unit
) {
    val imageCapture = capture ?: return

    val tempFile = java.io.File(context.cacheDir, "temp_capture_${System.currentTimeMillis()}.jpg")
    val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) {
                Toast.makeText(context, "फोटो त्रुटी: ${exc.message}", Toast.LENGTH_SHORT).show()
            }

            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                Thread {
                    try {
                        val decodeOptions = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        var bmp = BitmapFactory.decodeFile(tempFile.absolutePath, decodeOptions) ?: return@Thread

                        // १. अचूक दिशा
                        try {
                            val exif = android.media.ExifInterface(tempFile.absolutePath)
                            val orient = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
                            val matrix = android.graphics.Matrix()
                            when (orient) {
                                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                            }
                            if (!matrix.isIdentity) {
                                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                            }
                        } catch (_: Exception) {}

                        // २. फुल स्क्रीन क्रॉप
                        val isPortrait = (deviceOrientation == 0 || deviceOrientation == 180)
                        bmp = smartCropAspectRatio(bmp, aspectRatio, isPortrait)

                        // 💎 ३. निवडलेल्या Quality Mode नुसार प्रक्रिया (Natural, Crystal, Ultra)
                        bmp = applyQualityModeEngine(bmp, qualityMode)

                        // 💧 Lens Blur / Portrait
                        if (mode == "PORTRAIT" || activeFilter.contains("Lens Blur")) {
                            bmp = applyDSLRBokehEffect(bmp, blurLevel)
                        }
                        if (mode == "NIGHT") bmp = processNightPhoto(bmp)

                        // 🎨 Live Temp, Tint आणि Saturation
                        if (tempVal != 0f || tintVal != 0f || satVal != 0f) {
                            val cm = android.graphics.ColorMatrix()
                            if (satVal != 0f) {
                                val sMatrix = android.graphics.ColorMatrix()
                                sMatrix.setSaturation((1f + satVal / 100f).coerceAtLeast(0f))
                                cm.postConcat(sMatrix)
                            }
                            val rT = (tempVal * 0.22f) + (tintVal * 0.12f)
                            val gT = -(tintVal * 0.22f)
                            val bT = -(tempVal * 0.22f) + (tintVal * 0.12f)
                            val tempTintMat = android.graphics.ColorMatrix(floatArrayOf(
                                1f, 0f, 0f, 0f, rT,
                                0f, 1f, 0f, 0f, gT,
                                0f, 0f, 1f, 0f, bT,
                                0f, 0f, 0f, 1f, 0f
                            ))
                            cm.postConcat(tempTintMat)

                            val gradedBmp = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                            val gCanvas = android.graphics.Canvas(gradedBmp)
                            gCanvas.drawBitmap(bmp, 0f, 0f, android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(cm); isAntiAlias = true })
                            bmp = gradedBmp
                        }

                        // 🎨 Selective Color (पांढरी फरशी पांढरीच ठेवणे)
                        if (rVal != 128f || gVal != 128f || bVal != 128f) {
                            val rRatio = (rVal - 128f) / 128f
                            val gRatio = (gVal - 128f) / 128f
                            val bRatio = (bVal - 128f) / 128f

                            val w = bmp.width
                            val h = bmp.height
                            val pixels = IntArray(w * h)
                            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                            val hsv = FloatArray(3)

                            for (i in pixels.indices) {
                                val c = pixels[i]
                                val r = (c shr 16) and 0xFF
                                val g = (c shr 8) and 0xFF
                                val b = c and 0xFF
                                val maxC = maxOf(r, maxOf(g, b))
                                val minC = minOf(r, minOf(g, b))
                                if (maxC - minC < 35) continue

                                android.graphics.Color.colorToHSV(c, hsv)
                                val hue = hsv[0]
                                if ((hue <= 30 || hue >= 330) && rRatio != 0f) {
                                    hsv[1] = (hsv[1] * (1f + rRatio * 0.9f)).coerceIn(0f, 1f)
                                    pixels[i] = android.graphics.Color.HSVToColor((c ushr 24), hsv)
                                } else if (hue in 80f..160f && gRatio != 0f) {
                                    hsv[1] = (hsv[1] * (1f + gRatio * 0.9f)).coerceIn(0f, 1f)
                                    pixels[i] = android.graphics.Color.HSVToColor((c ushr 24), hsv)
                                } else if (hue in 190f..260f && bRatio != 0f) {
                                    hsv[1] = (hsv[1] * (1f + bRatio * 0.9f)).coerceIn(0f, 1f)
                                    pixels[i] = android.graphics.Color.HSVToColor((c ushr 24), hsv)
                                }
                            }
                            val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            outBmp.setPixels(pixels, 0, w, 0, 0, w, h)
                            bmp = outBmp
                        }

                        // सर्व फिल्टर्स
                        bmp = when {
                            activeFilter == "Normal" -> bmp
                            activeFilter.contains("Lens Blur") -> bmp
                            activeFilter.contains("NVG") -> applyMilitaryNightVision(bmp)
                            activeFilter.contains("Color Night") -> applyColorNightVision(bmp)
                            activeFilter.contains("Cinematic") -> applyMasterCinematic9in1(bmp)
                            activeFilter.contains("Glow") -> applyVideoGlowHalo(bmp)
                            activeFilter.contains("Retro") -> applyVintage70sFilter(bmp)
                            activeFilter.contains("Night") || activeFilter.contains("Cyberpunk") -> applyCyberpunkNeonFilter(bmp)
                            activeFilter.contains("4K") -> applyAIEnhancement(bmp)
                            activeFilter == "Golden" -> applyGoldenHourGlow(bmp)
                            activeFilter == "Noir" -> applyNoirFilter(bmp)
                            activeFilter == "Astro" -> applyAstroPhoto(bmp)
                            activeFilter == "Macro" -> applyMacroSharpen(bmp)
                            activeFilter in listOf("Rich Contrast", "Vibrant", "Warm", "Cool") -> applyPhotographicStyle(bmp, activeFilter)
                            else -> bmp
                        }

                        if (addWatermark) bmp = addBrandWatermark(bmp)

                        saveFinalProcessedBitmap(context, bmp, "${mode}_${activeFilter.replace(" ", "_")}")
                        tempFile.delete()

                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onSuccess(bmp)
                        }

                    } catch (e: Throwable) {
                        (context as? android.app.Activity)?.runOnUiThread {
                            Toast.makeText(context, "इफेक्ट त्रुटी: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
        }
    )
}

// 💎 ३ Quality Modes इंजिन (Natural, Crystal, Ultra)
fun applyCrystalClarityV2(
    src: Bitmap,
    mode: String = "Crystal"
): Bitmap {

    require(!src.isRecycled) {
        "Source bitmap is recycled"
    }

    val w = src.width
    val h = src.height

    require(w > 0 && h > 0) {
        "Invalid bitmap dimensions"
    }

    var sharpnessWeight = 0.55f

    // ==================================
    // 1. COLOR GRADING ENGINE
    // ==================================

    val cm = ColorMatrix()

    when (mode) {

        "Natural" -> {

            // 🌿 NATURAL MODE

            cm.setSaturation(1.0f)

            val mat = ColorMatrix(
                floatArrayOf(
                    1.01f, 0f, 0f, 0f, 0f,
                    0f, 1.01f, 0f, 0f, 0f,
                    0f, 0f, 1.01f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )

            cm.postConcat(mat)

            sharpnessWeight = 0.25f
        }

        "Ultra" -> {

            // ⚡ ULTRA MODE

            cm.setSaturation(1.08f)

            val mat = ColorMatrix(
                floatArrayOf(
                    1.08f, 0f, 0f, 0f, -10f,
                    0f, 1.08f, 0f, 0f, -10f,
                    0f, 0f, 1.08f, 0f, -10f,
                    0f, 0f, 0f, 1f, 0f
                )
            )

            cm.postConcat(mat)

            sharpnessWeight = 0.55f
        }

        else -> {

            // 💎 CRYSTAL MODE

            cm.setSaturation(1.15f)

            val mat = ColorMatrix(
                floatArrayOf(
                    1.09f, 0f, 0f, 0f, -11f,
                    0f, 1.09f, 0f, 0f, -11f,
                    0f, 0f, 1.09f, 0f, -11f,
                    0f, 0f, 0f, 1f, 0f
                )
            )

            cm.postConcat(mat)

            sharpnessWeight = 0.65f
        }
    }

    // ==================================
    // 2. COLOR FILTER
    // ==================================

    val output = Bitmap.createBitmap(
        w,
        h,
        Bitmap.Config.ARGB_8888
    )

    val paint = Paint(
        Paint.FILTER_BITMAP_FLAG
    ).apply {

        colorFilter = ColorMatrixColorFilter(cm)

    }

    Canvas(output).drawBitmap(
        src,
        0f,
        0f,
        paint
    )

    // ==================================
    // 3. MEMORY OPTIMIZED SHARPENING
    // ==================================

    if (w < 3 || h < 3) {
        return output
    }

    val topRow = IntArray(w)
    val centerRow = IntArray(w)
    val bottomRow = IntArray(w)

    var pendingRow: IntArray? = null

    for (y in 1 until h - 1) {

        // Original neighboring rows read first.
        output.getPixels(
            topRow, 0, w,
            0, y - 1, w, 1
        )

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.roundToInt

fun applyCrystalClarityV2(
    src: Bitmap,
    mode: String = "Crystal"
): Bitmap {

    require(!src.isRecycled) {
        "Source bitmap is recycled"
    }

    val w = src.width
    val h = src.height

    require(w > 0 && h > 0) {
        "Invalid bitmap dimensions"
    }

    var sharpnessWeight = 0.55f

    // ==================================
    // 1. COLOR GRADING ENGINE
    // ==================================

    val cm = ColorMatrix()

    when (mode) {

        "Natural" -> {

            // 🌿 NATURAL MODE

            cm.setSaturation(1.0f)

            val mat = ColorMatrix(
                floatArrayOf(
                    1.01f, 0f, 0f, 0f, 0f,
                    0f, 1.01f, 0f, 0f, 0f,
                    0f, 0f, 1.01f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )

            cm.postConcat(mat)

            sharpnessWeight = 0.25f
        }

        "Ultra" -> {

            // ⚡ ULTRA MODE

            cm.setSaturation(1.08f)

            val mat = ColorMatrix(
                floatArrayOf(
                    1.08f, 0f, 0f, 0f, -10f,
                    0f, 1.08f, 0f, 0f, -10f,
                    0f, 0f, 1.08f, 0f, -10f,
                    0f, 0f, 0f, 1f, 0f
                )
            )

            cm.postConcat(mat)

            sharpnessWeight = 0.55f
        }

        else -> {

            // 💎 CRYSTAL MODE

            cm.setSaturation(1.15f)

            val mat = ColorMatrix(
                floatArrayOf(
                    1.09f, 0f, 0f, 0f, -11f,
                    0f, 1.09f, 0f, 0f, -11f,
                    0f, 0f, 1.09f, 0f, -11f,
                    0f, 0f, 0f, 1f, 0f
                )
            )

            cm.postConcat(mat)

            sharpnessWeight = 0.65f
        }
    }

    // ==================================
    // 2. COLOR FILTER
    // ==================================

    val output = Bitmap.createBitmap(
        w,
        h,
        Bitmap.Config.ARGB_8888
    )

    val paint = Paint(
        Paint.FILTER_BITMAP_FLAG
    ).apply {

        colorFilter = ColorMatrixColorFilter(cm)

    }

    Canvas(output).drawBitmap(
        src,
        0f,
        0f,
        paint
    )

    // ==================================
    // 3. MEMORY OPTIMIZED SHARPENING
    // ==================================

    if (w < 3 || h < 3) {
        return output
    }

    val topRow = IntArray(w)
    val centerRow = IntArray(w)
    val bottomRow = IntArray(w)

    var pendingRow: IntArray? = null

    for (y in 1 until h - 1) {

        // Original neighboring rows read first.
        output.getPixels(
            topRow, 0, w,
            0, y - 1, w, 1
        )

        output.getPixels(
            centerRow, 0, w,
            0, y, w, 1
        )

        output.getPixels(
            bottomRow, 0, w,
            0, y + 1, w, 1
        )

        // Write previous row only after reading it.
        pendingRow?.let {

            output.setPixels(
                it, 0, w,
                0, y - 1, w, 1
            )

        }

        val resultRow = centerRow.copyOf()

        for (x in 1 until w - 1) {

            val c = centerRow[x]

            val u = topRow[x]
            val d = bottomRow[x]

            val l = centerRow[x - 1]
            val r = centerRow[x + 1]

            val a = (c ushr 24) and 255

            val cr = (c shr 16) and 255
            val cg = (c shr 8) and 255
            val cb = c and 255

            // Neighbor average
            val nr = (
                ((u shr 16) and 255) +
                ((d shr 16) and 255) +
                ((l shr 16) and 255) +
                ((r shr 16) and 255)
            ) / 4f

            val ng = (
                ((u shr 8) and 255) +
                ((d shr 8) and 255) +
                ((l shr 8) and 255) +
                ((r shr 8) and 255)
            ) / 4f

            val nb = (
                (u and 255) +
                (d and 255) +
                (l and 255) +
                (r and 255)
            ) / 4f

            // Detail sharpening
            val red = (
                cr + (cr - nr) * sharpnessWeight
            ).roundToInt().coerceIn(0, 255)

            val green = (
                cg + (cg - ng) * sharpnessWeight
            ).roundToInt().coerceIn(0, 255)

            val blue = (
                cb + (cb - nb) * sharpnessWeight
            ).roundToInt().coerceIn(0, 255)

            resultRow[x] =
                (a shl 24) or
                (red shl 16) or
                (green shl 8) or
                blue
        }

        pendingRow = resultRow
    }

    // Write the final processed row.
    pendingRow?.let {

        output.setPixels(
            it, 0, w,
            0, h - 2, w, 1
        )

    }

    return output
}
fun smartCropAspectRatio(src: Bitmap, aspect: String, isPortrait: Boolean): Bitmap {
    val w = src.width
    val h = src.height

    if (aspect == "1:1") {
        val s = minOf(w, h)
        return Bitmap.createBitmap(src, (w - s) / 2, (h - s) / 2, s, s)
    }

    val ratio = when (aspect) {
        "Full" -> if (isPortrait) (9f / 20f) else (20f / 9f)
        "16:9" -> if (isPortrait) (9f / 16f) else (16f / 9f)
        else -> if (isPortrait) (3f / 4f) else (4f / 3f)
    }

    val currentRatio = w.toFloat() / h.toFloat()

    return if (currentRatio > ratio) {
        val targetWidth = (h * ratio).toInt()
        val xOffset = ((w - targetWidth) / 2).coerceAtLeast(0)
        Bitmap.createBitmap(src, xOffset, 0, targetWidth.coerceAtMost(w), h)
    } else {
        val targetHeight = (w / ratio).toInt()
        val yOffset = ((h - targetHeight) / 2).coerceAtLeast(0)
        Bitmap.createBitmap(src, 0, yOffset, w, targetHeight.coerceAtMost(h))
    }
}
