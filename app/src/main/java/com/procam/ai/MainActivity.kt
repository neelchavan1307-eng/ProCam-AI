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
    var currentZoom by remember { mutableFloatStateOf(1f) }
    var isZoomDialVisible by remember { mutableStateOf(false) }
    var selectedMode by remember { mutableStateOf("PHOTO") }
    var activeFilter by remember { mutableStateOf("Normal") }
    var selectedVideoResolution by remember { mutableStateOf("4K") }
    var isActionModeOn by remember { mutableStateOf(false) }
    var isQuickMenuOpen by remember { mutableStateOf(false) }
    var selectedAspect by remember { mutableStateOf("4:3") }
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
    var wbKelvin by remember { mutableFloatStateOf(5500f) }
    var isWbAuto by remember { mutableStateOf(true) }
    var manualFocusDistance by remember { mutableFloatStateOf(0f) }
    var isFocusAuto by remember { mutableStateOf(true) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var portraitBlur by remember { mutableFloatStateOf(0.65f) }
    var countdownValue by remember { mutableIntStateOf(0) }
    var isCountingDown by remember { mutableStateOf(false) }
    var exposureComp by remember { mutableFloatStateOf(0f) } // FIX 1: हा missing होता
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
                if (selectedMode == "PRO") { applyProManualSettings() }
            } catch (e: Exception) {
                Toast.makeText(context, "कॅमेरा त्रुटी: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    LaunchedEffect(manualIso, isIsoAuto, shutterDenom, isShutterAuto, evValue, isWbAuto, manualFocusDistance, isFocusAuto) {
        if (selectedMode == "PRO") { applyProManualSettings() }
    }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermissions) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { _, _, zoomChange, _ -> applySafeZoom(currentZoom * zoomChange) }
            }.pointerInput(Unit) {
                detectTapGestures { offset ->
                    focusPoint = offset
                    val factory = previewView.meteringPointFactory
                    val point = factory.createPoint(offset.x, offset.y)
                    camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                    scope.launch { delay(3000); focusPoint = null }
                }
            })
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
        focusPoint?.let { pos ->
            Row(modifier = Modifier.offset(pos.x.dp - 35.dp, pos.y.dp - 35.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(70.dp).border(1.5.dp, Color(0xFFFFCC00), RoundedCornerShape(4.dp)))
                Spacer(modifier = Modifier.width(6.dp))
                Box(modifier = Modifier.size(32.dp).pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        val delta = -dragAmount / 20f
                        exposureComp = (exposureComp + delta).coerceIn(-4f, 4f)
                        camera?.cameraControl?.setExposureCompensationIndex(exposureComp.toInt())
                    }
                }, contentAlignment = Alignment.Center) { Text("☀️", fontSize = 16.sp) }
            }
        }
        // ... (तुमचा बाकीचा वरचा Filter Patti + Quick Menu + Bottom Strip चा कोड जसा आहे तसाच इथे येईल, जागे अभावी मी शटर बटण पासून पुढे देतोय, तो मधला भाग तुमच्याकडे आहेच)
        Column(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (selectedMode == "PRO") {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).background(Color(0x99111622), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("ISO", color = Color.Gray, fontSize = 9.sp); Text(if (isIsoAuto) "AUTO" else "${manualIso.toInt()}", color = if (selectedProTab == "ISO") Color(0xFFFFCC00) else Color.White, fontSize = 11.sp) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("S", color = Color.Gray, fontSize = 9.sp); Text(if (isShutterAuto) "AUTO" else "1/${shutterDenom.toInt()}", color = if (selectedProTab == "S") Color(0xFFFFCC00) else Color.White, fontSize = 11.sp) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("EV", color = Color.Gray, fontSize = 9.sp); Text(String.format("%+.1f", evValue), color = if (selectedProTab == "EV") Color(0xFFFFCC00) else Color.White, fontSize = 11.sp) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("WB", color = Color.Gray, fontSize = 9.sp); Text(if (isWbAuto) "AUTO" else "${wbKelvin.toInt()}K", color = if (selectedProTab == "WB") Color(0xFFFFCC00) else Color.White, fontSize = 11.sp) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("FOCUS", color = Color.Gray, fontSize = 9.sp); Text(if (isFocusAuto) "AUTO" else "MF", color = if (selectedProTab == "FOCUS") Color(0xFFFFCC00) else Color.White, fontSize = 11.sp) }
                }
            }
            val triggerAction = {
                if (selectedMode == "VIDEO") {
                    if (!isRecording) {
                        videoCapture?.let { activeRecording = startRealVideoRecording(context, it, activeFilter) { isRecording = false }; isRecording = true }
                    } else { activeRecording?.stop(); activeRecording = null; isRecording = false }
                } else {
                    if (timerDuration > 0) {
                        scope.launch {
                            isCountingDown = true
                            for (i in timerDuration downTo 1) { countdownValue = i; delay(1000) }
                            isCountingDown = false
                            captureProcessedPhoto(context, imageCapture, selectedMode, activeFilter, portraitBlur, isWatermarkOn, selectedAspect)
                        }
                    } else { captureProcessedPhoto(context, imageCapture, selectedMode, activeFilter, portraitBlur, isWatermarkOn, selectedAspect) }
                }
            }
            LaunchedEffect(Unit) { registerVolumeTrigger { triggerAction() } }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(45.dp))
                Box(modifier = Modifier.size(76.dp).border(4.dp, Color.White, CircleShape).padding(5.dp).background(if (selectedMode == "VIDEO" || isRecording) Color.Red else Color.White, CircleShape).pointerInput(selectedMode) {
                    detectTapGestures(onTap = { triggerAction() }, onLongPress = { if (!isRecording) { videoCapture?.let { activeRecording = startRealVideoRecording(context, it, activeFilter) { isRecording = false }; isRecording = true } } })
                })
                Box(modifier = Modifier.size(45.dp).background(Color(0x66000000), CircleShape).clickable { lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK }, contentAlignment = Alignment.Center) { Text("🔄", fontSize = 20.sp) }
            }
        }
    }
}
fun startRealVideoRecording(context: Context, videoCapture: VideoCapture<Recorder>, activeFilter: String, onComplete: () -> Unit): Recording {
    val cleanFilterName = activeFilter.replace(" ", "_").replace("(", "").replace(")", "").replace("-", "_")
    val name = "ProCam_Cinema_${cleanFilterName}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}"
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ProCam AI") }
    }
    val mediaStoreOutput = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(contentValues).build()
    val pending = videoCapture.output.prepareRecording(context, mediaStoreOutput)
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { pending.withAudioEnabled() }
    return pending.start(ContextCompat.getMainExecutor(context)) { event ->
        when (event) {
            is VideoRecordEvent.Start -> { Toast.makeText(context, "🔴 4K [ $activeFilter ] रेकॉर्डिंग सुरू!", Toast.LENGTH_SHORT).show() }
            is VideoRecordEvent.Finalize -> { onComplete(); if (!event.hasError()) { Toast.makeText(context, "🎥 4K सिनेमा सेव्ह!", Toast.LENGTH_LONG).show() } else { Toast.makeText(context, "त्रुटी: ${event.error}", Toast.LENGTH_SHORT).show() } }
        }
    }
}
fun captureProcessedPhoto(context: Context, capture: ImageCapture?, mode: String, activeFilter: String, blurLevel: Float, addWatermark: Boolean, aspectRatio: String) {
    val imageCapture = capture ?: return
    val tempFile = java.io.File(context.cacheDir, "temp_${System.currentTimeMillis()}.jpg")
    val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()
    imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
        override fun onError(exc: ImageCaptureException) { Toast.makeText(context, "फोटो त्रुटी: ${exc.message}", Toast.LENGTH_SHORT).show() }
        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
            Thread {
                try {
                    var bmp = BitmapFactory.decodeFile(tempFile.absolutePath) ?: return@Thread
                    bmp = cropToRealAspectRatio(bmp, aspectRatio)
                    if (mode == "PORTRAIT") bmp = applyDSLRBokehEffect(bmp, blurLevel)
                    if (mode == "NIGHT") bmp = processNightPhoto(bmp)
                    bmp = when {
                        activeFilter == "Normal" -> bmp
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
                        else -> applyPhotographicStyle(bmp, activeFilter)
                    }
                    if (addWatermark) bmp = addBrandWatermark(bmp)
                    saveFinalProcessedBitmap(context, bmp, "${mode}_${activeFilter.replace(" ", "_")}")
                    tempFile.delete()
                } catch (e: Throwable) { (context as? android.app.Activity)?.runOnUiThread { Toast.makeText(context, "इफेक्ट त्रुटी: ${e.message}", Toast.LENGTH_SHORT).show() } }
            }.start()
        }
    })
}
// ---- खाली सगळे FIXED FILTERS ----
fun cropToRealAspectRatio(src: Bitmap, ratio: String): Bitmap {
    if (ratio == "Full" || ratio == "4:3") return src
    val w = src.width; val h = src.height
    return try {
        when (ratio) {
            "16:9" -> { val nh = w * 9 / 16; Bitmap.createBitmap(src, 0, (h-nh)/2, w, nh) }
            "1:1" -> { val s = minOf(w,h); Bitmap.createBitmap(src, (w-s)/2, (h-s)/2, s, s) }
            else -> src
        }
    } catch (_: Exception) { src }
}
fun applyMilitaryNightVision(src: Bitmap): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(res)
    val cm = ColorMatrix(floatArrayOf(0f,0f,0f,0f,0f, 0.35f,0.70f,0.15f,0f,48f, 0f,0f,0f,0f,0f, 0f,0f,0f,1f,0f))
    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
    return res
}
fun applyColorNightVision(src: Bitmap): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val cm = ColorMatrix(floatArrayOf(1.38f,0f,0f,0f,42f, 0f,1.38f,0f,0f,42f, 0f,0f,1.38f,0f,42f, 0f,0f,0f,1f,0f))
    Canvas(res).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
    return res
}
fun applyMasterCinematic9in1(src: Bitmap): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val cm = ColorMatrix(floatArrayOf(1.1f,0f,0f,0f,-10f, 0f,1.05f,0f,0f,-10f, 0f,0f,0.9f,0f,5f, 0f,0f,0f,1f,0f))
    Canvas(res).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
    return res
}
fun applyDSLRBokehEffect(src: Bitmap, blur: Float): Bitmap = src // Demo - Blur library नंतर add करू
fun processNightPhoto(src: Bitmap): Bitmap = applyColorNightVision(src)
fun applyVideoGlowHalo(src: Bitmap): Bitmap = applyGoldenHourGlow(src)
fun applyVintage70sFilter(src: Bitmap): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val cm = ColorMatrix(floatArrayOf(1.2f,0.2f,0f,0f,0f, 0.2f,1.0f,0f,0f,0f, 0f,0f,0.8f,0f,0f, 0f,0f,0f,1f,0f))
    Canvas(res).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
    return res
}
fun applyCyberpunkNeonFilter(src: Bitmap): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val cm = ColorMatrix(floatArrayOf(1.2f,0f,0f,0f,0f, 0f,0.8f,0f,0f,0f, 0f,0f,1.4f,0f,20f, 0f,0f,0f,1f,0f))
    Canvas(res).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
    return res
}
fun applyAIEnhancement(src: Bitmap): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val cm = ColorMatrix(floatArrayOf(1.15f,0f,0f,0f,0f, 0f,1.15f,0f,0f,0f, 0f,0f,1.15f,0f,0f, 0f,0f,0f,1f,0f))
    Canvas(res).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
    return res
}
fun applyGoldenHourGlow(src: Bitmap): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val cm = ColorMatrix(floatArrayOf(1.3f,0f,0f,0f,15f, 0f,1.15f,0f,0f,5f, 0f,0f,0.8f,0f,0f, 0f,0f,0f,1f,0f))
    Canvas(res).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
    return res
}
fun applyNoirFilter(src: Bitmap): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val cm = ColorMatrix(); cm.setSaturation(0f)
    Canvas(res).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
    return res
}
fun applyAstroPhoto(src: Bitmap): Bitmap = applyAIEnhancement(src)
fun applyMacroSharpen(src: Bitmap): Bitmap = applyAIEnhancement(src)
fun applyPhotographicStyle(src: Bitmap, style: String): Bitmap {
    return when(style) {
        "Warm" -> applyGoldenHourGlow(src)
        "Cool" -> applyCyberpunkNeonFilter(src)
        "Vibrant" -> applyAIEnhancement(src)
        "Rich Contrast" -> applyMasterCinematic9in1(src)
        else -> src
    }
}
fun addBrandWatermark(src: Bitmap): Bitmap {
    val res = src.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(res)
    val paint = Paint().apply { color = android.graphics.Color.WHITE; textSize = src.width * 0.03f; typeface = android.graphics.Typeface.DEFAULT_BOLD; alpha = 150 }
    canvas.drawText("ProCam AI • ${SimpleDateFormat("dd MMM yyyy", Locale.US).format(java.util.Date())}", 20f, src.height - 30f, paint)
    return res
}
fun saveFinalProcessedBitmap(context: Context, bmp: Bitmap, prefix: String) {
    try {
        val name = "${prefix}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ProCam AI") }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let { context.contentResolver.openOutputStream(it)?.use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 98, out) } }
        (context as? android.app.Activity)?.runOnUiThread { Toast.makeText(context, "📸 $prefix सेव्ह झाला!", Toast.LENGTH_SHORT).show() }
    } catch (e: Exception) { (context as? android.app.Activity)?.runOnUiThread { Toast.makeText(context, "Save Error: ${e.message}", Toast.LENGTH_SHORT).show() } }
}
