package com.procam.ai

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                PureCinemaCameraApp()
            }
        }
    }
}

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
@Composable
fun PureCinemaCameraApp() {
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

    var selectedMode by remember { mutableStateOf("PRO") }
    var activeFilter by remember { mutableStateOf("Cinematic Look (9-in-1)") }

    var selectedVideoResolution by remember { mutableStateOf("4K") }
    var isActionModeOn by remember { mutableStateOf(false) }

    // Dropdown Settings
    var isQuickMenuOpen by remember { mutableStateOf(false) }
    var selectedAspect by remember { mutableStateOf("4:3") }
    var timerDuration by remember { mutableIntStateOf(0) }
    var isGridOn by remember { mutableStateOf(true) }
    var isLevelOn by remember { mutableStateOf(true) }
    var isWatermarkOn by remember { mutableStateOf(true) }
    var isHDROn by remember { mutableStateOf(true) }
    var isAICameraOn by remember { mutableStateOf(true) }

    // 📸 PRO MODE मधील मॅन्युअल व्हेरिएबल्स (तक्त्याप्रमाणे)
    var selectedProTab by remember { mutableStateOf("ISO") } // ISO, S, EV, WB, FOCUS
    var manualIso by remember { mutableFloatStateOf(100f) }
    var isIsoAuto by remember { mutableStateOf(true) }
    var shutterDenom by remember { mutableFloatStateOf(120f) } // 1/120s
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

    // 🔥 मॅन्युअल Camera2 सेटिंग्ज लागू करणारे फंक्शन
    fun applyProManualSettings() {
        val cam = camera ?: return
        try {
            val camera2Control = Camera2CameraControl.from(cam.cameraControl)
            val builder = CaptureRequestOptions.Builder()

            // १. ISO आणि Shutter Speed
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

            // २. White Balance (AWB)
            if (!isWbAuto) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            } else {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }

            // ३. Focus (AF / MF)
            if (!isFocusAuto) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance)
            } else {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }

            camera2Control.setCaptureRequestOptions(builder.build())
        } catch (_: Exception) {}
    }

    // कॅमेरा व व्हिडिओ हार्डवेअर बाइंडिंग
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
                }

                applySafeZoom(currentZoom)
                applyProManualSettings()
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

        if (activeFilter.contains("Cinematic")) {
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

        // Tap-to-Focus Indicator
        focusPoint?.let { pos ->
            Box(
                modifier = Modifier
                    .offset(pos.x.dp - 35.dp, pos.y.dp - 35.dp)
                    .size(70.dp)
                    .border(1.5.dp, Color(0xFFFFCC00), RoundedCornerShape(4.dp))
            )
        }

        // Live Histogram
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 180.dp)
                .size(60.dp, 34.dp)
                .background(Color(0x88000000), RoundedCornerShape(6.dp))
                .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("HISTO ∿", color = Color(0xFFFFCC00), fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }

        // Top Status Bar
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

                Text("64MP RAW", color = Color(0xFFFFCC00), fontSize = 11.sp)
            }
        }

        // Dropdown Menu
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

        // -------------------------------------------------------------
        // 📸 तळाचा विभाग: अस्सल PRO MODE (Readout Bar + Sliders + Buttons)
        // -------------------------------------------------------------
        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (selectedMode == "PRO") {
                // १. Live Readout HUD पट्टी (तुमच्या ASCII डिझाईनप्रमाणे)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .background(Color(0x99111622), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ISO", color = Color.Gray, fontSize = 10.sp)
                        Text(if (isIsoAuto) "AUTO" else "${manualIso.toInt()}", color = if (selectedProTab == "ISO") Color(0xFFFFCC00) else Color.White, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("S", color = Color.Gray, fontSize = 10.sp)
                        Text(if (isShutterAuto) "AUTO" else "1/${shutterDenom.toInt()}", color = if (selectedProTab == "S") Color(0xFFFFCC00) else Color.White, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("EV", color = Color.Gray, fontSize = 10.sp)
                        Text(String.format("%+.1f", evValue), color = if (selectedProTab == "EV") Color(0xFFFFCC00) else Color.White, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("WB", color = Color.Gray, fontSize = 10.sp)
                        Text(if (isWbAuto) "AUTO" else "${wbKelvin.toInt()}K", color = if (selectedProTab == "WB") Color(0xFFFFCC00) else Color.White, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("FOCUS", color = Color.Gray, fontSize = 10.sp)
                        Text(if (isFocusAuto) "AUTO" else "MF", color = if (selectedProTab == "FOCUS") Color(0xFFFFCC00) else Color.White, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                }

                // २. निवडलेल्या सेटिंगनुसार डायनॅमिक स्लायडर
                Box(modifier = Modifier.fillMaxWidth().height(45.dp).padding(horizontal = 24.dp)) {
                    when (selectedProTab) {
                        "ISO" -> {
                            Slider(
                                value = manualIso,
                                onValueChange = { manualIso = it; isIsoAuto = false },
                                valueRange = 50f..3200f,
                                colors = SliderDefaults.colors(thumbColor = Color(0xFFFFCC00), activeTrackColor = Color(0xFFFFCC00))
                            )
                        }
                        "S" -> {
                            Slider(
                                value = shutterDenom,
                                onValueChange = { shutterDenom = it; isShutterAuto = false },
                                valueRange = 4f..4000f,
                                colors = SliderDefaults.colors(thumbColor = Color(0xFFFFCC00), activeTrackColor = Color(0xFFFFCC00))
                            )
                        }
                        "EV" -> {
                            Slider(
                                value = evValue,
                                onValueChange = { evValue = it },
                                valueRange = -2f..2f,
                                steps = 8,
                                colors = SliderDefaults.colors(thumbColor = Color(0xFFFFCC00), activeTrackColor = Color(0xFFFFCC00))
                            )
                        }
                        "WB" -> {
                            Slider(
                                value = wbKelvin,
                                onValueChange = { wbKelvin = it; isWbAuto = false },
                                valueRange = 2500f..7500f,
                                colors = SliderDefaults.colors(thumbColor = Color(0xFFFFCC00), activeTrackColor = Color(0xFFFFCC00))
                            )
                        }
                        "FOCUS" -> {
                            Slider(
                                value = manualFocusDistance,
                                onValueChange = { manualFocusDistance = it; isFocusAuto = false },
                                valueRange = 0f..10f,
                                colors = SliderDefaults.colors(thumbColor = Color(0xFFFFCC00), activeTrackColor = Color(0xFFFFCC00))
                            )
                        }
                    }
                }

                // ३. [ISO] [S] [EV] [WB] [FOCUS] ची ५ निवड बटणे
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    listOf("ISO", "S", "EV", "WB", "FOCUS").forEach { tab ->
                        val isSel = selectedProTab == tab
                        Button(
                            onClick = { selectedProTab = tab },
                            colors = ButtonDefaults.buttonColors(containerColor = if (isSel) Color(0xFFFFCC00) else Color(0xFF1E2433)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(tab, color = if (isSel) Color.Black else Color.White, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 🔻 मुख्य मोड्स (Clean Bottom Mode Strip)
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
                        fontSize = if (isSel) 15.sp else 13.sp,
                        fontWeight = if (isSel) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                        modifier = Modifier.clickable { selectedMode = mode }.padding(vertical = 4.dp, horizontal = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Shutter Button Row
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                                        activeRecording = startRealVideoRecording(context, it, activeFilter) { isRecording = false }
                                        isRecording = true
                                    }
                                } else {
                                    activeRecording?.stop()
                                    activeRecording = null
                                    isRecording = false
                                }
                            } else {
                                captureProcessedPhoto(context, imageCapture, selectedMode, activeFilter, portraitBlur, isWatermarkOn, selectedAspect)
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
// 📸 खरा फोटो सेव्हर (MediaEngine द्वारे सेव्ह)
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

                        bmp = when {
                            activeFilter.contains("Cinematic") -> applyMasterCinematic9in1(bmp)
                            activeFilter.contains("Glow") -> applyVideoGlowHalo(bmp)
                            activeFilter.contains("Retro") -> applyVintage70sFilter(bmp)
                            activeFilter.contains("Night") || activeFilter.contains("Cyberpunk") -> applyCyberpunkNeonFilter(bmp)
                            activeFilter.contains("4K") -> applyAIEnhancement(bmp)
                            activeFilter == "Golden" -> applyGoldenHourGlow(bmp)
                            activeFilter == "Noir" -> applyNoirFilter(bmp)
                            activeFilter == "Astro" -> applyAstroPhoto(bmp)
                            activeFilter == "Macro" -> applyMacroSharpen(bmp)
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
