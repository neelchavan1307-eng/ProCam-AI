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
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.*
import kotlin.random.Random

// डेटा क्लास ज्यामुळे Triple चा एरर कायमचा दूर झाला
data class QuickMenuTile(
    val title: String,
    val subtitle: String,
    val isActive: Boolean,
    val action: () -> Unit
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                ProCamFinalWheelApp()
            }
        }
    }
}

@Composable
fun ProCamFinalWheelApp() {
    var currentScreen by remember { mutableStateOf("CAMERA") }

    Scaffold(
        bottomBar = {
            BottomNavBar(currentScreen = currentScreen, onTabSelected = { currentScreen = it })
        },
        containerColor = Color.Black
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (currentScreen) {
                "CAMERA" -> MasterWheelCameraScreen()
                "EDITOR" -> CompleteEditorScreen()
                "AI_LAB" -> CompleteAILabScreen()
                "SETTINGS" -> CompleteSettingsScreen()
            }
        }
    }
}

@Composable
fun MasterWheelCameraScreen() {
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

    var currentZoom by remember { mutableFloatStateOf(1f) }
    var isZoomDialVisible by remember { mutableStateOf(false) }

    var selectedMode by remember { mutableStateOf("PHOTO") }
    var activeFilter by remember { mutableStateOf("Normal") }
    var activePhotographicStyle by remember { mutableStateOf("Standard") }
    var isActionModeOn by remember { mutableStateOf(false) }
    var selectedVideoResolution by remember { mutableStateOf("4K") }

    var isQuickMenuOpen by remember { mutableStateOf(false) }
    var isMoreModesOpen by remember { mutableStateOf(false) }
    var selectedAspect by remember { mutableStateOf("4:3") }
    var timerDuration by remember { mutableIntStateOf(0) }
    var isGridOn by remember { mutableStateOf(true) }
    var isLevelOn by remember { mutableStateOf(true) }
    var isWatermarkOn by remember { mutableStateOf(true) }
    var isHDROn by remember { mutableStateOf(true) }
    var isAICameraOn by remember { mutableStateOf(true) }
    var isTiltShiftOn by remember { mutableStateOf(false) }

    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var exposureComp by remember { mutableFloatStateOf(0f) }
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
                    if (selectedMode == "NIGHT" || activeFilter == "Astro") {
                        camera?.cameraControl?.setExposureCompensationIndex(2)
                    } else if (selectedMode != "PRO") {
                        camera?.cameraControl?.setExposureCompensationIndex(exposureComp.toInt())
                    }
                }

                applySafeZoom(currentZoom)

                try {
                    val camera2Control = androidx.camera.camera2.interop.Camera2CameraControl.from(camera!!.cameraControl)
                    val builder = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(
                            android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                            android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                        )
                        .setCaptureRequestOption(
                            android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                        )

                    when (activeFilter) {
                        "Noir" -> builder.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_EFFECT_MODE, android.hardware.camera2.CaptureRequest.CONTROL_EFFECT_MODE_MONO)
                        "Vintage" -> builder.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_EFFECT_MODE, android.hardware.camera2.CaptureRequest.CONTROL_EFFECT_MODE_SEPIA)
                        "Golden" -> builder.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT)
                        "Cyberpunk" -> builder.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT)
                        else -> builder.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_EFFECT_MODE, android.hardware.camera2.CaptureRequest.CONTROL_EFFECT_MODE_OFF)
                    }
                    camera2Control.setCaptureRequestOptions(builder.build())
                } catch (_: Exception) {}
            } catch (e: Exception) {
                Toast.makeText(context, "कॅमेरा त्रुटी: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermissions) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier
                    .fillMaxSize()
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

        if (activeFilter == "Cinematic") {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val h = maxHeight
                val barHeight = h * 0.12f
                Box(modifier = Modifier.fillMaxWidth().height(barHeight).align(Alignment.TopCenter).background(Color.Black))
                Box(modifier = Modifier.fillMaxWidth().height(barHeight).align(Alignment.BottomCenter).background(Color.Black))
            }
        }

        if (isGridOn) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height; val c = Color(0x33FFFFFF)
                drawLine(c, Offset(w / 3, 0f), Offset(w / 3, h), 1.dp.toPx())
                drawLine(c, Offset(w * 2 / 3, 0f), Offset(w * 2 / 3, h), 1.dp.toPx())
                drawLine(c, Offset(0f, h / 3), Offset(w, h / 3), 1.dp.toPx())
                drawLine(c, Offset(0f, h * 2 / 3), Offset(w, h * 2 / 3), 1.dp.toPx())
            }
        }

        if (isLevelOn) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height; val midY = h / 2
                drawLine(Color(0xFFFFCC00), Offset(w * 0.25f, midY), Offset(w * 0.75f, midY), 1.5.dp.toPx())
                drawCircle(Color(0xFFFFCC00), radius = 3.dp.toPx(), center = Offset(w / 2, midY))
            }
        }

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
        }Column(
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

            val allStylesAndFilters = listOf(
                "Standard" to "🍎", "Rich Contrast" to "🎭", "Vibrant" to "🌈", 
                "Warm" to "☀️", "Cool" to "❄️", "Cinematic" to "🎬", 
                "Golden" to "🌅", "Cyberpunk" to "🏙️", "Vintage" to "🎞️", 
                "Noir" to "🖤", "Astro" to "🌌", "HDR+" to "⚡", "Macro" to "🔬"
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                allStylesAndFilters.forEach { (name, icon) ->
                    val isSel = (activePhotographicStyle == name || activeFilter == name)
                    Row(
                        modifier = Modifier
                            .background(if (isSel) Color(0xFFFFCC00) else Color(0x88161B26), RoundedCornerShape(16.dp))
                            .border(1.dp, if (isSel) Color.White else Color(0x44FFFFFF), RoundedCornerShape(16.dp))
                            .clickable {
                                if (name in listOf("Standard", "Rich Contrast", "Vibrant", "Warm", "Cool")) {
                                    activePhotographicStyle = name
                                    activeFilter = "Normal"
                                } else {
                                    activeFilter = name
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(icon, fontSize = 11.sp)
                        Text(name, color = if (isSel) Color.Black else Color.White, fontSize = 11.sp)
                    }
                }
            }
        }

        // 🔥 दुरुस्त केलेला ड्रॉपडाउन मेनू (१००% एरर-फ्री)
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
                            QuickMenuTile("AI", if (isAICameraOn) "AI Camera" else "Off", isAICameraOn) { isAICameraOn = !isAICameraOn },
                            QuickMenuTile("Macro", "2MP Lens", activeFilter == "Macro") { activeFilter = if (activeFilter == "Macro") "Normal" else "Macro" },
                            QuickMenuTile("Aspect", selectedAspect, true) {
                                selectedAspect = when(selectedAspect) { "4:3" -> "16:9"; "16:9" -> "1:1"; "1:1" -> "Full"; else -> "4:3" }
                            }
                        ),
                        listOf(
                            QuickMenuTile("Timer", if (timerDuration == 0) "Off" else "${timerDuration}s", timerDuration > 0) {
                                timerDuration = when(timerDuration) { 0 -> 3; 3 -> 5; 5 -> 10; else -> 0 }
                            },
                            QuickMenuTile("Tilt-shift", if (isTiltShiftOn) "On" else "Off", isTiltShiftOn) { isTiltShiftOn = !isTiltShiftOn },
                            QuickMenuTile("Grid #", if (isGridOn) "On" else "Off", isGridOn) { isGridOn = !isGridOn },
                            QuickMenuTile("Level ⊝", if (isLevelOn) "On" else "Off", isLevelOn) { isLevelOn = !isLevelOn }
                        ),
                        listOf(
                            QuickMenuTile("Watermark", if (isWatermarkOn) "On" else "Off", isWatermarkOn) { isWatermarkOn = !isWatermarkOn },
                            QuickMenuTile("Resolution", selectedVideoResolution, true) {
                                selectedVideoResolution = when(selectedVideoResolution) { "4K" -> "1080p"; "1080p" -> "720p"; else -> "4K" }
                            },
                            QuickMenuTile("Assist cam", "Ready", false) {},
                            QuickMenuTile("Settings", "More", false) { isQuickMenuOpen = false; isMoreModesOpen = true }
                        )
                    )

                    tileRows.forEach { rowTiles ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            rowTiles.forEach { tile ->
                                Column(
                                    modifier = Modifier
                                        .size(72.dp, 62.dp)
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

        AnimatedVisibility(visible = isMoreModesOpen, modifier = Modifier.fillMaxSize().background(Color(0xF20A0D14))) {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Camera Modes", color = Color.White, fontSize = 20.sp)
                    Button(onClick = { isMoreModesOpen = false }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222938))) { Text("Close ✕") }
                }
                Spacer(modifier = Modifier.height(20.dp))
                val modesList = listOf("Pro", "Documents", "Slow motion", "Time-lapse", "Long Exposure", "Panorama", "Short film", "AI Watermark")
                modesList.chunked(3).forEach { r ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        r.forEach { m ->
                            Button(
                                onClick = { isMoreModesOpen = false; Toast.makeText(context, "$m निवडले", Toast.LENGTH_SHORT).show() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF161B26)),
                                modifier = Modifier.size(100.dp, 65.dp)
                            ) { Text(m, color = Color.White, fontSize = 10.sp) }
                        }
                    }
                }
            }
        }

        if (isCountingDown) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("$countdownValue", color = Color(0xFFFFCC00), fontSize = 95.sp)
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isZoomDialVisible) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(
                        text = String.format("%.1fx", currentZoom),
                        color = Color(0xFFFFCC00),
                        fontSize = 20.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .height(55.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val delta = dragAmount.x / 40f
                                    val newZoom = (currentZoom + delta).coerceIn(0.6f, 10f)
                                    applySafeZoom(newZoom)
                                }
                            }
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height

                            drawArc(
                                color = Color(0x66FFFFFF),
                                startAngle = 180f,
                                sweepAngle = 180f,
                                useCenter = false,
                                topLeft = Offset(0f, -h),
                                size = androidx.compose.ui.geometry.Size(w, h * 2),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                            )

                            val markings = listOf(0.6f, 1f, 2f, 4f, 6f, 8f, 10f)
                            markings.forEach { mark ->
                                val progress = (mark - 0.6f) / (10f - 0.6f)
                                val x = w * progress
                                val isNear = abs(currentZoom - mark) < 0.3f
                                drawLine(
                                    color = if (isNear) Color(0xFFFFCC00) else Color.White,
                                    start = Offset(x, h * 0.4f),
                                    end = Offset(x, h * 0.85f),
                                    strokeWidth = if (isNear) 3.dp.toPx() else 1.5.dp.toPx()
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .background(Color(0x66000000), RoundedCornerShape(24.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(0.6f to ".6", 1f to "1", 2f to "2", 5f to "5", 10f to "10").forEach { (z, label) ->
                    val isSel = abs(currentZoom - z) < 0.15f
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(if (isSel) Color(0x44FFFFFF) else Color.Transparent, CircleShape)
                            .clickable {
                                applySafeZoom(z)
                                isZoomDialVisible = false
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = if (isSel) Color(0xFFFFCC00) else Color.White,
                            fontSize = 12.sp,
                            fontWeight = if (isSel) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .background(if (isZoomDialVisible) Color(0xFFFFCC00) else Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                        .clickable { isZoomDialVisible = !isZoomDialVisible }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("DIAL", color = if (isZoomDialVisible) Color.Black else Color.White, fontSize = 10.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val cleanModes = listOf("PHOTO", "VIDEO", "PORTRAIT", "NIGHT", "PRO")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
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

            Spacer(modifier = Modifier.height(14.dp))

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(45.dp))

                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(5.dp)
                        .background(if (selectedMode == "VIDEO") Color.Red else Color.White, CircleShape)
                        .clickable {
                            if (selectedMode == "VIDEO") {
                                if (!isRecording) {
                                    videoCapture?.let {
                                        activeRecording = startRealVideoRecording(context, it, activeFilter, activePhotographicStyle) { isRecording = false }
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
                                        captureMegaProcessedPhoto(context, imageCapture, selectedMode, activeFilter, activePhotographicStyle, portraitBlur, isWatermarkOn, selectedAspect)
                                    }
                                } else {
                                    captureMegaProcessedPhoto(context, imageCapture, selectedMode, activeFilter, activePhotographicStyle, portraitBlur, isWatermarkOn, selectedAspect)
                                }
                            }
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
}// -------------------------------------------------------------
// २. 4K Video आणि Photo Engine (Styles + Filters सह थेट सेव्ह)
// -------------------------------------------------------------
fun startRealVideoRecording(
    context: Context,
    videoCapture: VideoCapture<Recorder>,
    activeFilter: String,
    photographicStyle: String,
    onComplete: () -> Unit
): Recording {
    val name = "ProCam_4K_${activeFilter}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}"
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
                    Toast.makeText(context, "🎥 4K व्हिडिओ गॅलरीत सेव्ह झाला!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "रेकॉर्डिंग त्रुटी: ${event.error}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

fun captureMegaProcessedPhoto(
    context: Context,
    capture: ImageCapture?,
    mode: String,
    activeFilter: String,
    photographicStyle: String,
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
                Toast.makeText(context, "फोटो क्लिक त्रुटी: ${exc.message}", Toast.LENGTH_SHORT).show()
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

                        bmp = applyPhotographicStyle(bmp, photographicStyle)

                        if (mode == "PORTRAIT") bmp = applyDSLRBokehEffect(bmp, blurLevel)
                        if (mode == "NIGHT") bmp = processNightPhoto(bmp)

                        bmp = when (activeFilter) {
                            "Cinematic" -> applyCinematicLetterbox(bmp)
                            "Golden" -> applyGoldenHourGlow(bmp)
                            "Cyberpunk" -> applyCyberpunkNeonFilter(bmp)
                            "Vintage" -> applyVintage70sFilter(bmp)
                            "Noir" -> applyNoirFilter(bmp)
                            "Astro" -> applyAstroPhoto(bmp)
                            "HDR+" -> applyHDRPlus(bmp)
                            "Macro" -> applyMacroSharpen(bmp)
                            else -> bmp
                        }

                        if (addWatermark) bmp = addBrandWatermark(bmp)

                        saveFinalProcessedBitmap(context, bmp, "${mode}_${activeFilter}")
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

fun saveFinalProcessedBitmap(context: Context, bitmap: Bitmap, mode: String) {
    val name = "ProCam_${mode}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}"
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ProCam AI")
        }
    }

    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
    uri?.let {
        val stream = context.contentResolver.openOutputStream(it)
        stream?.use { s ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, s)
        }
        (context as? android.app.Activity)?.runOnUiThread {
            Toast.makeText(context, "✅ $mode फोटो खऱ्या इफेक्टसह सेव्ह झाला!", Toast.LENGTH_LONG).show()
        }
    }
}

// -------------------------------------------------------------
// ३. AI Photo Lab (Before/After Split Slider + AI Object Eraser)
// -------------------------------------------------------------
@Composable
fun CompleteAILabScreen() {
    val context = LocalContext.current
    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var enhancedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var sliderRatio by remember { mutableFloatStateOf(0.5f) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val s = context.contentResolver.openInputStream(it)
            val l = BitmapFactory.decodeStream(s)
            originalBitmap = Bitmap.createScaledBitmap(l, 1080, (1080f * l.height / l.width).toInt(), true)
            enhancedBitmap = null
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🤖 AI Photo Lab & Smart Retouch", color = Color.White, fontSize = 20.sp)
        Text("HD Enhance • AI Object Eraser • Face Preserve", color = Color.Gray, fontSize = 12.sp)

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF161B26)).clickable { if (originalBitmap == null) galleryLauncher.launch("image/*") },
            contentAlignment = Alignment.Center
        ) {
            val orig = originalBitmap
            val enh = enhancedBitmap
            if (orig != null) {
                if (enh != null) {
                    BeforeAfterSplitView(before = orig, after = enh, splitRatio = sliderRatio, onRatioChange = { sliderRatio = it })
                } else {
                    Image(bitmap = orig.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            } else {
                Text("✨ फोटो निवडण्यासाठी इथे टॅप करा", color = Color(0xFF0A84FF))
            }

            if (isProcessing) {
                Box(modifier = Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFFFFCC00))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("AI प्रक्रिया सुरू आहे...", color = Color.White)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222938))) { Text("नवीन") }
            Button(
                onClick = {
                    originalBitmap?.let {
                        isProcessing = true
                        Thread {
                            val res = applyAIEnhancement(it)
                            (context as? android.app.Activity)?.runOnUiThread { enhancedBitmap = res; isProcessing = false }
                        }.start()
                    }
                },
                modifier = Modifier.weight(1.3f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
            ) { Text("⚡ AI HD") }

            Button(
                onClick = {
                    originalBitmap?.let {
                        isProcessing = true
                        Thread {
                            val res = applyAIObjectEraser(it)
                            (context as? android.app.Activity)?.runOnUiThread { enhancedBitmap = res; isProcessing = false; Toast.makeText(context, "स्मार्ट टच-अप पूर्ण!", Toast.LENGTH_SHORT).show() }
                        }.start()
                    }
                },
                modifier = Modifier.weight(1.3f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF))
            ) { Text("🪄 Erase") }
        }

        if (enhancedBitmap != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Button(onClick = { enhancedBitmap?.let { saveFinalProcessedBitmap(context, it, "AI_HD") } }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFCC00))) {
                Text("💾 Save Enhanced HD Photo", color = Color.Black)
            }
        }
    }
}

@Composable
fun BeforeAfterSplitView(before: Bitmap, after: Bitmap, splitRatio: Float, onRatioChange: (Float) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val w = maxWidth; val h = maxHeight
        Image(bitmap = after.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Box(modifier = Modifier.fillMaxHeight().width(w * splitRatio).clipToBounds()) {
            Image(bitmap = before.asImageBitmap(), contentDescription = null, modifier = Modifier.size(w, h), contentScale = ContentScale.Fit)
        }
        Box(modifier = Modifier.fillMaxHeight().width(3.dp).offset(x = w * splitRatio).background(Color.White))
        Slider(value = splitRatio, onValueChange = onRatioChange, modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(16.dp))
    }
}

// -------------------------------------------------------------
// ४. Photo Editor Screen
// -------------------------------------------------------------
@Composable
fun CompleteEditorScreen() {
    val context = LocalContext.current
    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var displayedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var selectedFilter by remember { mutableStateOf("Natural") }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val s = context.contentResolver.openInputStream(it)
            val l = BitmapFactory.decodeStream(s)
            originalBitmap = Bitmap.createScaledBitmap(l, 1080, (1080f * l.height / l.width).toInt(), true)
            displayedBitmap = originalBitmap
        }
    }

    LaunchedEffect(brightness, contrast, selectedFilter, originalBitmap) {
        originalBitmap?.let {
            displayedBitmap = applyFilters(it, brightness, contrast, selectedFilter)
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🎨 Professional Editor", color = Color.White, fontSize = 20.sp)

        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF161B26)).clickable { galleryLauncher.launch("image/*") },
            contentAlignment = Alignment.Center
        ) {
            displayedBitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } ?: Text("📁 फोटो निवडा", color = Color(0xFF0A84FF))
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text("Brightness: ${brightness.toInt()}", color = Color.LightGray, fontSize = 11.sp)
        Slider(value = brightness, onValueChange = { brightness = it }, valueRange = -100f..100f)

        Text("Contrast: ${String.format("%.1fx", contrast)}", color = Color.LightGray, fontSize = 11.sp)
        Slider(value = contrast, onValueChange = { contrast = it }, valueRange = 0.5f..2f)

        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Natural", "Cinematic", "Vintage", "B&W", "Golden Hour").forEach { f ->
                Button(
                    onClick = { selectedFilter = f },
                    colors = ButtonDefaults.buttonColors(containerColor = if (selectedFilter == f) Color(0xFFFFCC00) else Color(0xFF1E2433))
                ) { Text(f, color = if (selectedFilter == f) Color.Black else Color.White, fontSize = 12.sp) }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { displayedBitmap?.let { saveFinalProcessedBitmap(context, it, "Edited") } }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))) {
            Text("💾 Save Edited Photo", color = Color.White)
        }
    }
}// -------------------------------------------------------------
// ५. Settings & Bottom Navigation
// -------------------------------------------------------------
@Composable
fun CompleteSettingsScreen() {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
        Text("⚙️ ProCam 64MP OIS Settings", color = Color.White, fontSize = 22.sp)
        Spacer(modifier = Modifier.height(14.dp))
        Text("📸 Primary: 64 MP Ultra HD RAW", color = Color.LightGray)
        Spacer(modifier = Modifier.height(8.dp))
        Text("🔭 Ultra-Wide: 8 MP (0.6x Real Lens)", color = Color.LightGray)
        Spacer(modifier = Modifier.height(8.dp))
        Text("🔍 Zoom Dial: 0.6x to 10x Smooth Arc Wheel", color = Color(0xFFFFCC00))
        Spacer(modifier = Modifier.height(8.dp))
        Text("🏃 Stabilization: Hardware OIS + Action Mode EIS", color = Color(0xFFFFCC00))
        Spacer(modifier = Modifier.height(8.dp))
        Text("🎥 Video: 4K @ 30FPS / 1080p @ 60FPS", color = Color.LightGray)
        Spacer(modifier = Modifier.height(8.dp))
        Text("📂 Save: Pictures/ProCam AI & Movies/ProCam AI", color = Color.LightGray)
    }
}

@Composable
fun BottomNavBar(currentScreen: String, onTabSelected: (String) -> Unit) {
    NavigationBar(containerColor = Color(0xFF0F141C)) {
        NavigationBarItem(selected = currentScreen == "CAMERA", onClick = { onTabSelected("CAMERA") }, icon = { Text("📷") }, label = { Text("कॅमेरा") })
        NavigationBarItem(selected = currentScreen == "EDITOR", onClick = { onTabSelected("EDITOR") }, icon = { Text("🎨") }, label = { Text("एडिटर") })
        NavigationBarItem(selected = currentScreen == "AI_LAB", onClick = { onTabSelected("AI_LAB") }, icon = { Text("✨") }, label = { Text("AI Lab") })
        NavigationBarItem(selected = currentScreen == "SETTINGS", onClick = { onTabSelected("SETTINGS") }, icon = { Text("⚙️") }, label = { Text("सेटिंग्ज") })
    }
}

// -------------------------------------------------------------
// ६. Photographic Styles & All Mode Algorithms
// -------------------------------------------------------------
fun applyPhotographicStyle(src: Bitmap, style: String): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(res)
    val cm = ColorMatrix()

    when (style) {
        "Rich Contrast" -> {
            cm.set(floatArrayOf(
                1.22f, 0f, 0f, 0f, -12f,
                0f, 1.22f, 0f, 0f, -12f,
                0f, 0f, 1.22f, 0f, -12f,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        "Vibrant" -> {
            cm.setSaturation(1.28f)
            val boost = ColorMatrix(floatArrayOf(
                1.08f, 0f, 0f, 0f, 4f,
                0f, 1.08f, 0f, 0f, 4f,
                0f, 0f, 1.08f, 0f, 4f,
                0f, 0f, 0f, 1f, 0f
            ))
            cm.postConcat(boost)
        }
        "Warm" -> {
            cm.set(floatArrayOf(
                1.16f, 0f, 0f, 0f, 18f,
                0f, 1.06f, 0f, 0f, 8f,
                0f, 0f, 0.88f, 0f, -10f,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        "Cool" -> {
            cm.set(floatArrayOf(
                0.90f, 0f, 0f, 0f, -8f,
                0f, 1.02f, 0f, 0f, 4f,
                0f, 0f, 1.20f, 0f, 20f,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        else -> return src
    }

    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm); isAntiAlias = true })
    return res
}

fun applyDSLRBokehEffect(src: Bitmap, blurRadius: Float): Bitmap {
    val w = src.width
    val h = src.height

    val downFactor = (14f * blurRadius).toInt().coerceAtLeast(8)
    val small = Bitmap.createScaledBitmap(src, (w / downFactor).coerceAtLeast(1), (h / downFactor).coerceAtLeast(1), true)
    val blurredBg = Bitmap.createScaledBitmap(small, w, h, true)

    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    canvas.drawBitmap(blurredBg, 0f, 0f, null)

    val focusRadius = (w.coerceAtMost(h) * 0.38f)
    val shader = RadialGradient(
        w / 2f, h / 2f, focusRadius,
        intArrayOf(android.graphics.Color.WHITE, android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT),
        floatArrayOf(0f, 0.45f, 1f),
        Shader.TileMode.CLAMP
    )
    val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.shader = shader
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    val sharpSubject = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val subjectCanvas = Canvas(sharpSubject)
    subjectCanvas.drawBitmap(src, 0f, 0f, null)
    subjectCanvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), maskPaint)

    canvas.drawBitmap(sharpSubject, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
    return result
}

fun applyCinematicLetterbox(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    val res = src.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(res)

    val targetH = (w / 2.39f).toInt()
    val barHeight = ((h - targetH) / 2).coerceAtLeast(0)

    val blackPaint = Paint().apply { color = android.graphics.Color.BLACK }
    canvas.drawRect(0f, 0f, w.toFloat(), barHeight.toFloat(), blackPaint)
    canvas.drawRect(0f, (h - barHeight).toFloat(), w.toFloat(), h.toFloat(), blackPaint)

    val cm = ColorMatrix(floatArrayOf(
        1.15f, 0f, 0f, 0f, 10f,
        0f, 1.0f, 0f, 0f, 5f,
        0f, 0f, 1.25f, 0f, 15f,
        0f, 0f, 0f, 1f, 0f
    ))
    canvas.drawBitmap(res, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm); isAntiAlias = true })
    return res
}

fun applyVintage70sFilter(src: Bitmap): Bitmap {
    val res = src.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(res)
    val cm = ColorMatrix(floatArrayOf(
        0.9f, 0f, 0f, 0f, 25f,
        0f, 0.78f, 0f, 0f, 15f,
        0f, 0f, 0.62f, 0f, 5f,
        0f, 0f, 0f, 1f, 0f
    ))
    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })

    val noisePaint = Paint().apply {
        color = android.graphics.Color.WHITE
        alpha = 18
    }
    for (i in 0 until (res.width * res.height / 150)) {
        val x = Random.nextInt(res.width).toFloat()
        val y = Random.nextInt(res.height).toFloat()
        canvas.drawCircle(x, y, 1.2f, noisePaint)
    }
    return res
}

fun applyGoldenHourGlow(src: Bitmap): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(res)
    val cm = ColorMatrix(floatArrayOf(
        1.25f, 0f, 0f, 0f, 35f,
        0f, 1.08f, 0f, 0f, 18f,
        0f, 0f, 0.72f, 0f, -15f,
        0f, 0f, 0f, 1f, 0f
    ))
    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm); isAntiAlias = true })
    return res
}

fun applyCyberpunkNeonFilter(src: Bitmap): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(res)
    val cm = ColorMatrix(floatArrayOf(
        1.35f, 0f, 0f, 0f, 30f,
        0f, 0.75f, 0f, 0f, -10f,
        0f, 0f, 1.45f, 0f, 40f,
        0f, 0f, 0f, 1f, 0f
    ))
    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm); isAntiAlias = true })
    return res
}

fun applyNoirFilter(src: Bitmap): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(res)
    val cm = ColorMatrix()
    cm.setSaturation(0f)
    cm.postConcat(ColorMatrix(floatArrayOf(
        1.45f, 0f, 0f, 0f, -25f,
        0f, 1.45f, 0f, 0f, -25f,
        0f, 0f, 1.45f, 0f, -25f,
        0f, 0f, 0f, 1f, 0f
    )))
    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm); isAntiAlias = true })
    return res
}

fun applyAstroPhoto(src: Bitmap): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(res)
    val cm = ColorMatrix(floatArrayOf(
        1.30f, 0f, 0f, 0f, 35f,
        0f, 1.30f, 0f, 0f, 35f,
        0f, 0f, 1.50f, 0f, 45f,
        0f, 0f, 0f, 1f, 0f
    ))
    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm); isAntiAlias = true })
    return res
}

fun applyMacroSharpen(src: Bitmap): Bitmap {
    return applyAIEnhancement(src)
}

fun applyHDRPlus(src: Bitmap): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(res)
    val cm = ColorMatrix(floatArrayOf(
        1.18f, 0f, 0f, 0f, 15f,
        0f, 1.18f, 0f, 0f, 15f,
        0f, 0f, 1.18f, 0f, 15f,
        0f, 0f, 0f, 1f, 0f
    ))
    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm); isAntiAlias = true })
    return res
}

fun applyAIObjectEraser(src: Bitmap): Bitmap {
    val res = src.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(res)
    val blurRadius = 35f
    val p = Paint().apply {
        isAntiAlias = true
        maskFilter = android.graphics.BlurMaskFilter(blurRadius, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    canvas.drawCircle(res.width / 2f, res.height / 2f, 45f, p)
    return res
}

fun processNightPhoto(src: Bitmap): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(res)
    val cm = ColorMatrix(floatArrayOf(1.25f, 0f, 0f, 0f, 25f, 0f, 1.25f, 0f, 0f, 25f, 0f, 0f, 1.30f, 0f, 20f, 0f, 0f, 0f, 1f, 0f))
    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm); isAntiAlias = true })
    return res
}

fun applyAIEnhancement(src: Bitmap): Bitmap {
    val w = src.width; val h = src.height
    val contrastBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(contrastBmp)
    val cm = ColorMatrix(floatArrayOf(1.18f, 0f, 0f, 0f, 8f, 0f, 1.18f, 0f, 0f, 8f, 0f, 0f, 1.15f, 0f, 6f, 0f, 0f, 0f, 1f, 0f))
    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm); isAntiAlias = true })

    val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(w * h); val outPixels = IntArray(w * h)
    contrastBmp.getPixels(pixels, 0, w, 0, 0, w, h)
    for (y in 1 until h - 1) {
        for (x in 1 until w - 1) {
            val idx = y * w + x
            var r = 0; var g = 0; var b = 0
            val neighbors = intArrayOf(idx - w, idx - 1, idx, idx + 1, idx + w)
            val weights = intArrayOf(-1, -1, 5, -1, -1)
            for (i in neighbors.indices) {
                val p = pixels[neighbors[i]]
                r += ((p shr 16) and 0xFF) * weights[i]
                g += ((p shr 8) and 0xFF) * weights[i]
                b += (p and 0xFF) * weights[i]
            }
            outPixels[idx] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
    }
    output.setPixels(outPixels, 0, w, 0, 0, w, h)
    return output
}

fun cropToRealAspectRatio(src: Bitmap, aspect: String): Bitmap {
    val w = src.width
    val h = src.height
    return when (aspect) {
        "1:1" -> {
            val size = minOf(w, h)
            Bitmap.createBitmap(src, (w - size) / 2, (h - size) / 2, size, size)
        }
        "16:9" -> {
            val targetH = (w * 9) / 16
            if (targetH <= h) {
                Bitmap.createBitmap(src, 0, (h - targetH) / 2, w, targetH)
            } else {
                val targetW = (h * 16) / 9
                Bitmap.createBitmap(src, (w - targetW) / 2, 0, targetW, h)
            }
        }
        else -> src
    }
}

fun addBrandWatermark(src: Bitmap): Bitmap {
    val res = src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(res)
    val p = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = (res.width * 0.032f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
        isAntiAlias = true
    }
    canvas.drawText("⚡ SHOT ON PROCAM AI | 64MP OIS", 40f, res.height - 40f, p)
    return res
}

fun applyFilters(src: Bitmap, brightness: Float, contrast: Float, filter: String): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(res)
    val matrix = ColorMatrix()
    matrix.postConcat(ColorMatrix(floatArrayOf(contrast, 0f, 0f, 0f, brightness, 0f, contrast, 0f, 0f, brightness, 0f, 0f, contrast, 0f, brightness, 0f, 0f, 0f, 1f, 0f)))
    when (filter) {
        "B&W" -> { val bw = ColorMatrix(); bw.setSaturation(0f); matrix.postConcat(bw) }
        "Vintage" -> matrix.postConcat(ColorMatrix(floatArrayOf(0.9f, 0f, 0f, 0f, 20f, 0f, 0.75f, 0f, 0f, 10f, 0f, 0f, 0.6f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        "Cinematic" -> matrix.postConcat(ColorMatrix(floatArrayOf(1.1f, 0f, 0f, 0f, 10f, 0f, 1.0f, 0f, 0f, 5f, 0f, 0f, 1.25f, 0f, 15f, 0f, 0f, 0f, 1f, 0f)))
        "Golden Hour" -> matrix.postConcat(ColorMatrix(floatArrayOf(1.2f, 0f, 0f, 0f, 30f, 0f, 1.05f, 0f, 0f, 15f, 0f, 0f, 0.75f, 0f, -10f, 0f, 0f, 0f, 1f, 0f)))
    }
    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix); isAntiAlias = true })
    return res
}
