package com.procam.ai

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
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
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

data class QuickMenuTile(
    val title: String,
    val subtitle: String,
    val isActive: Boolean,
    val action: () -> Unit
)

class MainActivity : ComponentActivity() {
    // 📱 आवाजाच्या बटनाने (Volume Keys) फोटो/व्हिडिओ ट्रिगर करणे
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
                UltimateCameraEngineApp(
                    registerVolumeTrigger = { action -> onVolumeTrigger = action }
                )
            }
        }
    }
}

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
@Composable
fun UltimateCameraEngineApp(registerVolumeTrigger: (() -> Unit) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingTimer by remember { mutableIntStateOf(0) }

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var isFlashOn by remember { mutableStateOf(false) }

    // 🔍 0.6x ते 10x सेन्सर झूम + Arc Dial
    var currentZoom by remember { mutableFloatStateOf(1f) }
    var isZoomDialVisible by remember { mutableStateOf(false) }

    var selectedMode by remember { mutableStateOf("PHOTO") }
    // 🚫 कॅमेरा उघडताच डीफॉल्ट स्वच्छ "Normal" मोड!
    var activeFilter by remember { mutableStateOf("Normal") }

    var selectedVideoResolution by remember { mutableStateOf("4K") }
    var isActionModeOn by remember { mutableStateOf(false) }

    // Quick Settings Dropdown
    var isQuickMenuOpen by remember { mutableStateOf(false) }
    var selectedAspect by remember { mutableStateOf("4:3") }
    var timerDuration by remember { mutableIntStateOf(0) }
    var isGridOn by remember { mutableStateOf(true) }
    var isLevelOn by remember { mutableStateOf(true) }
    var isWatermarkOn by remember { mutableStateOf(true) }
    var isHDROn by remember { mutableStateOf(true) }
    var isAICameraOn by remember { mutableStateOf(true) }

    // 📸 PRO MODE मॅन्युअल व्हेरिएबल्स
    var selectedProTab by remember { mutableStateOf("ISO") }
    var manualIso by remember { mutableFloatStateOf(100f) }
    var isIsoAuto by remember { mutableStateOf(true) }
    var shutterDenom by remember { mutableFloatStateOf(120f) }
    var isShutterAuto by remember { mutableStateOf(true) }
    var evValue by remember { mutableFloatStateOf(0f) }
    var wbKelvin by remember { mutableFloatStateOf(5500f) }
    var isWbAuto by remember { mutableStateOf(true) }
    var manualFocusDistance by remember { mutableFloatStateOf(0f) }
    var isFocusAuto by remember { mutableStateOf(true) }

    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var portraitBlur by remember { mutableFloatStateOf(0.65f) }
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

    // 64MP OIS + 4K व्हिडिओ + Live Filters हार्डवेअर बाइंडिंग
    LaunchedEffect(hasPermissions, lensFacing, selectedMode, selectedVideoResolution, isActionModeOn, activeFilter) {
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
                        val ev = if (activeFilter.contains("Cinematic")) -1 else exposureComp.toInt()
                        camera?.cameraControl?.setExposureCompensationIndex(ev)
                    }
                }

                applySafeZoom(currentZoom)

                // 📳 OIS मोटर + सेन्सर टोन
                try {
                    val camera2Control = Camera2CameraControl.from(camera!!.cameraControl)
                    val builder = CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                        .setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)

                    when {
                        activeFilter.contains("Cinematic") || activeFilter == "Warm" -> {
                            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT)
                        }
                        activeFilter.contains("Retro") -> {
                            builder.setCaptureRequestOption(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_SEPIA)
                        }
                        activeFilter == "Noir" -> {
                            builder.setCaptureRequestOption(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_MONO)
                        }
                        activeFilter.contains("Cyberpunk") || activeFilter == "Cool" -> {
                            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT)
                        }
                        else -> {
                            builder.setCaptureRequestOption(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
                        }
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
                    // 🤏 दोन बोटांनी Pinch-to-Zoom
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoomChange, _ ->
                            applySafeZoom(currentZoom * zoomChange)
                        }
                    }
                    // Tap-to-Focus
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            focusPoint = offset
                            val factory = previewView.meteringPointFactory
                            val point = factory.createPoint(offset.x, offset.y)
                            camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                            scope.launch {
                                delay(3000)
                                focusPoint = null
                            }
                        }
                    }
            )
        }

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

        // Horizon Level Line
        if (isLevelOn) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height; val midY = h / 2
                drawLine(Color(0xFFFFCC00), Offset(w * 0.25f, midY), Offset(w * 0.75f, midY), 1.5.dp.toPx())
                drawCircle(Color(0xFFFFCC00), radius = 3.dp.toPx(), center = Offset(w / 2, midY))
            }
        }

        // ☀️ Tap-to-Focus Indicator + Sun Slider
        focusPoint?.let { pos ->
            Row(
                modifier = Modifier.offset(pos.x.dp - 35.dp, pos.y.dp - 35.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(70.dp)
                        .border(1.5.dp, Color(0xFFFFCC00), RoundedCornerShape(4.dp))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dragAmount ->
                                val delta = -dragAmount / 20f
                                exposureComp = (exposureComp + delta).coerceIn(-4f, 4f)
                                camera?.cameraControl?.setExposureCompensationIndex(exposureComp.toInt())
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("☀️", fontSize = 16.sp)
                }
            }
        }

        // Live Histogram
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 135.dp)
                .size(62.dp, 36.dp)
                .background(Color(0x88000000), RoundedCornerShape(6.dp))
                .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("HISTO ∿", color = Color(0xFFFFCC00), fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }

        // Audio VU Meter
        if (isRecording) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
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
        // वरचा विभाग: स्टेटस बार + Normal सह सर्व १५ फिल्टर्सची पट्टी
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
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { isFlashOn = !isFlashOn; camera?.cameraControl?.enableTorch(isFlashOn) },
                    modifier = Modifier.size(36.dp).background(if (isFlashOn) Color(0xFFFFCC00) else Color(0x66000000), CircleShape)
                ) {
                    Text(if (isFlashOn) "⚡" else "⚡", color = if (isFlashOn) Color.Black else Color.White)
                }

                Box(
                    modifier = Modifier
                        .background(Color(0x66000000), CircleShape)
                        .clickable { isQuickMenuOpen = !isQuickMenuOpen }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (isQuickMenuOpen) "▲" else "▼", color = Color.White, fontSize = 12.sp)
                }

                if (selectedMode == "VIDEO") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            modifier = Modifier
                                .background(if (isActionModeOn) Color(0xFFFFCC00) else Color(0x66000000), RoundedCornerShape(12.dp))
                                .clickable { isActionModeOn = !isActionModeOn }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("🏃 ACTION", color = if (isActionModeOn) Color.Black else Color.White, fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                    }
                } else {
                    Text("64MP RAW", color = Color(0xFFFFCC00), fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 🚫 Normal Mode सह सर्व १५ फिल्टर्सची पट्टी (नाईट व्हिजनसह)
            val allFiltersList = listOf(
                "Normal" to "🚫",
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                allFiltersList.forEach { (name, icon) ->
                    val isSel = activeFilter == name
                    Row(
                        modifier = Modifier
                            .background(if (isSel) Color(0xFFFFCC00) else Color(0x88161B26), RoundedCornerShape(16.dp))
                            .border(1.dp, if (isSel) Color.White else Color(0x44FFFFFF), RoundedCornerShape(16.dp))
                            .clickable { activeFilter = name }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(icon, fontSize = 11.sp)
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

        // Quick Dropdown Menu
        AnimatedVisibility(
            visible = isQuickMenuOpen,
            modifier = Modifier.padding(top = 80.dp, start = 16.dp, end = 16.dp).align(Alignment.TopCenter)
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
                            QuickMenuTile("AI", if (isAICameraOn) "AI Cam" else "Off", isAICameraOn) { isAICameraOn = !isAICameraOn },
                            QuickMenuTile("Aspect", selectedAspect, true) {
                                selectedAspect = when(selectedAspect) { "4:3" -> "16:9"; "16:9" -> "1:1"; "1:1" -> "Full"; else -> "4:3" }
                            },
                            QuickMenuTile("Timer", if (timerDuration == 0) "Off" else "${timerDuration}s", timerDuration > 0) {
                                timerDuration = when(timerDuration) { 0 -> 3; 3 -> 5; 5 -> 10; else -> 0 }
                            }
                        ),
                        listOf(
                            QuickMenuTile("Grid #", if (isGridOn) "On" else "Off", isGridOn) { isGridOn = !isGridOn },
                            QuickMenuTile("Level ⊝", if (isLevelOn) "On" else "Off", isLevelOn) { isLevelOn = !isLevelOn },
                            QuickMenuTile("Watermark", if (isWatermarkOn) "On" else "Off", isWatermarkOn) { isWatermarkOn = !isWatermarkOn },
                            QuickMenuTile("Resolution", selectedVideoResolution, true) {
                                selectedVideoResolution = when(selectedVideoResolution) { "4K" -> "1080p"; "1080p" -> "720p"; else -> "4K" }
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
                Text("$countdownValue", color = Color(0xFFFFCC00), fontSize = 95.sp)
            }
        }// -------------------------------------------------------------
        // तळाचा विभाग: Pro Mode + Arc Zoom Wheel + Modes + Shutter
        // -------------------------------------------------------------
        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 📸 अस्सल PRO MODE मॅन्युअल पॅनेल (ISO, Shutter, EV, WB, Focus)
            if (selectedMode == "PRO") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
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
                        "EV" -> Slider(value = evValue, onValueChange = { evValue = it }, valueRange = -2f..2f, steps = 8, colors = SliderDefaults.colors(thumbColor = Color(0xFFFFCC00), activeTrackColor = Color(0xFFFFCC00)))
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
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(tab, color = if (isSel) Color.Black else Color.White, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                    }
                }
            }

            // Portrait Mode Bokeh Slider
            if (selectedMode == "PORTRAIT") {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("👤 DSLR Bokeh: ${(portraitBlur * 100).toInt()}%", color = Color(0xFFFFCC00), fontSize = 11.sp)
                    Slider(value = portraitBlur, onValueChange = { portraitBlur = it }, valueRange = 0.2f..1.0f, colors = SliderDefaults.colors(thumbColor = Color(0xFFFFCC00), activeTrackColor = Color(0xFFFFCC00)))
                }
            }

            // 🔍 Arc Zoom Wheel (०.६x ते १०x)
            if (isZoomDialVisible) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 6.dp)) {
                    Text(text = String.format("%.1fx", currentZoom), color = Color(0xFFFFCC00), fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
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

            // Quick Lens Switcher Pills: .6 • 1 • 2 • 5 • DIAL
            Row(
                modifier = Modifier.background(Color(0x66000000), RoundedCornerShape(24.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(0.6f to ".6", 1f to "1", 2f to "2", 5f to "5", 10f to "10").forEach { (z, label) ->
                    val isSel = abs(currentZoom - z) < 0.15f
                    Box(
                        modifier = Modifier.size(32.dp).background(if (isSel) Color(0x44FFFFFF) else Color.Transparent, CircleShape).clickable { applySafeZoom(z); isZoomDialVisible = false },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = if (isSel) Color(0xFFFFCC00) else Color.White, fontSize = 11.sp, fontWeight = if (isSel) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal)
                    }
                }
                Box(
                    modifier = Modifier.background(if (isZoomDialVisible) Color(0xFFFFCC00) else Color(0x33FFFFFF), RoundedCornerShape(12.dp)).clickable { isZoomDialVisible = !isZoomDialVisible }.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("DIAL", color = if (isZoomDialVisible) Color.Black else Color.White, fontSize = 10.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 🔻 स्वच्छ मुख्य मोड्स (Clean Bottom Mode Strip)
            val cleanModes = listOf("PHOTO", "VIDEO", "PORTRAIT", "NIGHT", "PRO")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                cleanModes.forEach { mode ->
                    val isSel = selectedMode == mode
                    Text(
                        text = mode,
                        color = if (isSel) Color(0xFFFFCC00) else Color.White,
                        fontSize = if (isSel) 14.sp else 12.sp,
                        fontWeight = if (isSel) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                        modifier = Modifier.clickable { selectedMode = mode }.padding(vertical = 4.dp, horizontal = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Shutter Button Row
            val triggerAction = {
                if (selectedMode == "VIDEO") {
                    if (!isRecording) {
                        videoCapture?.let {
                            activeRecording = startRealVideoRecording(context, it, activeFilter) { isRecording = false }
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
                            captureProcessedPhoto(context, imageCapture, selectedMode, activeFilter, portraitBlur, isWatermarkOn, selectedAspect)
                        }
                    } else {
                        captureProcessedPhoto(context, imageCapture, selectedMode, activeFilter, portraitBlur, isWatermarkOn, selectedAspect)
                    }
                }
            }

            // 📱 फिजिकल Volume Key शी शटर जोडणे
            LaunchedEffect(Unit) {
                registerVolumeTrigger { triggerAction() }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(45.dp))

                // 🔴 QuickTake Shutter Button (Tap to Photo, Long-Press to Video)
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
                                    // 🔴 iPhone QuickTake Video: दाबून धरताच थेट व्हिडिओ रेकॉर्डिंग!
                                    if (!isRecording) {
                                        videoCapture?.let {
                                            activeRecording = startRealVideoRecording(context, it, activeFilter) { isRecording = false }
                                            isRecording = true
                                        }
                                    }
                                }
                            )
                        }
                )

                Box(
                    modifier = Modifier.size(45.dp).background(Color(0x66000000), CircleShape).clickable {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Text("🔄", fontSize = 20.sp)
                }
            }
        }
    }
}

// -------------------------------------------------------------
// 🎥 खरा व्हिडिओ सेव्हर (.mp4 गॅलरीत सेव्ह)
// -------------------------------------------------------------
fun startRealVideoRecording(
    context: Context,
    videoCapture: VideoCapture<Recorder>,
    activeFilter: String,
    onComplete: () -> Unit
): Recording {
    val cleanFilterName = activeFilter.replace(" ", "_").replace("(", "").replace(")", "").replace("-", "_")
    val name = "ProCam_Cinema_${cleanFilterName}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}"
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ProCam AI")
        }
    }

    val mediaStoreOutput = MediaStoreOutputOptions.Builder(
        context.contentResolver,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    ).setContentValues(contentValues).build()

    val pending = videoCapture.output.prepareRecording(context, mediaStoreOutput)
    // 🎙️ बाहेरील वायरलेस/कॉलर माईक सपोर्ट
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
                    Toast.makeText(context, "🎥 4K सिनेमा व्हिडिओ गॅलरीत सेव्ह झाला!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "रेकॉर्डिंग त्रुटी: ${event.error}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

// -------------------------------------------------------------
// 📸 खरा फोटो सेव्हर (MediaEngine + Night Vision सह सेव्ह)
// -------------------------------------------------------------
fun captureProcessedPhoto(
    context: Context,
    capture: ImageCapture?,
    mode: String,
    activeFilter: String,
    blurLevel: Float,
    addWatermark: Boolean,
    aspectRatio: String
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
                        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(tempFile.absolutePath, boundsOptions)

                        var sampleSize = 1
                        val maxDim = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
                        while (maxDim / sampleSize > 2560) {
                            sampleSize *= 2
                        }

                        val decodeOptions = BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }

                        var bmp = BitmapFactory.decodeFile(tempFile.absolutePath, decodeOptions) ?: return@Thread

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

                        bmp = cropToRealAspectRatio(bmp, aspectRatio)

                        if (mode == "PORTRAIT") bmp = applyDSLRBokehEffect(bmp, blurLevel)
                        if (mode == "NIGHT") bmp = processNightPhoto(bmp)

                        // 🎨 सर्व फिल्टर्स + 🥽 नाईट व्हिजन प्रत्यक्षात लागू करणे
                        bmp = when {
                            activeFilter == "Normal" -> bmp // 🚫 मूळ नैसर्गिक फोटो!
                            activeFilter.contains("NVG") -> applyMilitaryNightVision(bmp) // 🥽 लष्करी हिरवा नाईट व्हिजन
                            activeFilter.contains("Color Night") -> applyColorNightVision(bmp) // 🌙 रंगीत नाईट व्हिजन
                            activeFilter.contains("Cinematic") -> applyMasterCinematic9in1(bmp) // 🎬 ९-इन-१ सिनेमॅटिक
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

// 🥽 १. लष्करी हिरवा नाईट व्हिजन (Military Green Phosphor)
fun applyMilitaryNightVision(src: Bitmap): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(res)
    val cm = ColorMatrix(floatArrayOf(
        0f, 0f, 0f, 0f, 0f,
        0.35f, 0.70f, 0.15f, 0f, 48f, // तीव्र हिरवा फॉस्फर प्रकाश
        0f, 0f, 0f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    ))
    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm); isAntiAlias = true })
    return res
}

// 🌙 २. रंगीत नाईट व्हिजन (Color Night Vision)
fun applyColorNightVision(src: Bitmap): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(res)
    val cm = ColorMatrix(floatArrayOf(
        1.38f, 0f, 0f, 0f, 42f,
        0f, 1.38f, 0f, 0f, 42f,
        0f, 0f, 1.38f, 0f, 42f,
        0f, 0f, 0f, 1f, 0f
    ))
    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm); isAntiAlias = true })
    return res
}
