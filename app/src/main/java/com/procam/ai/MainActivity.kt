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
import androidx.compose.foundation.gestures.detectTapGestures
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0D14)) {
                ProCamFinal4KApp()
            }
        }
    }
}

@Composable
fun ProCamFinal4KApp() {
    var currentScreen by remember { mutableStateOf("CAMERA") }

    Scaffold(
        bottomBar = {
            BottomNavBar(currentScreen = currentScreen, onTabSelected = { currentScreen = it })
        },
        containerColor = Color(0xFF0A0D14)
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (currentScreen) {
                "CAMERA" -> Master4KCameraScreen()
                "EDITOR" -> CompleteEditorScreen()
                "AI_LAB" -> CompleteAILabScreen()
                "SETTINGS" -> CompleteSettingsScreen()
            }
        }
    }
}

// -------------------------------------------------------------
// १. ४K/१०८०p/७२०p कॅमेरा स्क्रीन (भाग १)
// -------------------------------------------------------------
@Composable
fun Master4KCameraScreen() {
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
    var selectedMode by remember { mutableStateOf("PHOTO") }

    // 4K, 1080p, 720p निवड
    var selectedVideoResolution by remember { mutableStateOf("4K") }

    // Quick Menu & Settings
    var isQuickMenuOpen by remember { mutableStateOf(false) }
    var isMoreModesOpen by remember { mutableStateOf(false) }
    var selectedAspect by remember { mutableStateOf("4:3") }
    var timerDuration by remember { mutableIntStateOf(0) }
    var isGridOn by remember { mutableStateOf(true) }
    var isLevelOn by remember { mutableStateOf(true) }
    var isWatermarkOn by remember { mutableStateOf(true) }

    // Pro, Portrait & Focus
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var portraitBlur by remember { mutableFloatStateOf(0.6f) }
    var exposureComp by remember { mutableFloatStateOf(0f) }
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

    // कॅमेरा व व्हिडिओ हार्डवेअर बाइंड करणे (4K, 1080p, 720p सह)
    LaunchedEffect(hasPermissions, lensFacing, selectedMode, selectedVideoResolution) {
        if (hasPermissions) {
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            cameraProvider.unbindAll()

            try {
                if (selectedMode == "VIDEO" || selectedMode == "PRO VIDEO") {
                    val targetQuality = when (selectedVideoResolution) {
                        "4K" -> Quality.UHD
                        "1080p" -> Quality.FHD
                        "720p" -> Quality.HD
                        else -> Quality.FHD
                    }
                    val qualitySelector = QualitySelector.from(
                        targetQuality,
                        FallbackStrategy.lowerQualityOrHigherThan(targetQuality)
                    )

                    val recorder = Recorder.Builder().setQualitySelector(qualitySelector).build()
                    val vc = VideoCapture.withOutput(recorder)
                    videoCapture = vc
                    camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, vc)
                } else {
                    val ic = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                    imageCapture = ic
                    camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, ic)
                    if (selectedMode == "NIGHT") camera?.cameraControl?.setExposureCompensationIndex(2)
                }
                camera?.cameraControl?.setZoomRatio(currentZoom)
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
                                delay(1800)
                                focusPoint = null
                            }
                        }
                    }
            )
        }

        // ३x३ Grid Lines
        if (isGridOn) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height; val c = Color(0x44FFFFFF)
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
                drawLine(Color(0xFFFFD60A), Offset(w * 0.25f, midY), Offset(w * 0.75f, midY), 1.5.dp.toPx())
                drawCircle(Color(0xFFFFD60A), radius = 3.dp.toPx(), center = Offset(w / 2, midY))
            }
        }

        // Tap-to-Focus Indicator (पिवळा फोकस बॉक्स)
        focusPoint?.let { pos ->
            Box(
                modifier = Modifier
                    .offset(pos.x.dp - 30.dp, pos.y.dp - 30.dp)
                    .size(60.dp)
                    .border(2.dp, Color(0xFFFFD60A), RoundedCornerShape(8.dp))
            )
        }

        // Top Bar (Flash, 4K/1080p Selector, Status, Settings)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { isFlashOn = !isFlashOn; camera?.cameraControl?.enableTorch(isFlashOn) },
                modifier = Modifier.size(38.dp).background(if (isFlashOn) Color(0xFFFFD60A) else Color(0x66000000), CircleShape)
            ) {
                Text(if (isFlashOn) "⚡" else "⚡", color = if (isFlashOn) Color.Black else Color.White)
            }

            // व्हिडिओ मोडमध्ये 720p / 1080p / 4K निवड पट्टी
            if (selectedMode == "VIDEO" || selectedMode == "PRO VIDEO") {
                if (isRecording) {
                    Row(
                        modifier = Modifier.background(Color(0xCCFF0000), RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("● REC $selectedVideoResolution ", color = Color.White, fontSize = 12.sp)
                        Text(String.format("%02d:%02d", recordingTimer / 60, recordingTimer % 60), color = Color.White, fontSize = 12.sp)
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .background(Color(0x99000000), RoundedCornerShape(20.dp))
                            .border(1.dp, Color(0xFFFFD60A), RoundedCornerShape(20.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("720p", "1080p", "4K").forEach { res ->
                            val isSel = selectedVideoResolution == res
                            Text(
                                text = res,
                                color = if (isSel) Color.Black else Color.White,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .background(if (isSel) Color(0xFFFFD60A) else Color.Transparent, RoundedCornerShape(12.dp))
                                    .clickable { selectedVideoResolution = res }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.background(Color(0x88000000), RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("ISO 400", color = Color(0xFFFFD60A), fontSize = 11.sp)
                    Text("1/125s", color = Color.White, fontSize = 11.sp)
                    Text(selectedAspect, color = Color(0xFF0A84FF), fontSize = 11.sp)
                }
            }

            IconButton(
                onClick = { isQuickMenuOpen = !isQuickMenuOpen },
                modifier = Modifier.size(38.dp).background(if (isQuickMenuOpen) Color(0xFFFFD60A) else Color(0x66000000), CircleShape)
            ) {
                Text("⚙️", fontSize = 14.sp)
            }
        }

        // Pro Mode Exposure Slider
        if (selectedMode == "PRO") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterEnd)
                    .padding(horizontal = 20.dp)
            ) {
                Text("Exposure: ${exposureComp.toInt()}", color = Color(0xFFFFD60A), fontSize = 12.sp)
                Slider(
                    value = exposureComp,
                    onValueChange = {
                        exposureComp = it
                        camera?.cameraControl?.setExposureCompensationIndex(it.toInt())
                    },
                    valueRange = -4f..4f,
                    steps = 8
                )
            }
        }

        // Portrait Mode Bokeh Slider
        if (selectedMode == "PORTRAIT") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 150.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("👤 DSLR Bokeh: ${(portraitBlur * 100).toInt()}%", color = Color(0xFF0A84FF), fontSize = 12.sp)
                Slider(
                    value = portraitBlur,
                    onValueChange = { portraitBlur = it },
                    valueRange = 0.2f..1.0f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF0A84FF), activeTrackColor = Color(0xFF0A84FF))
                )
            }
        }// Quick Dropdown Menu
        AnimatedVisibility(
            visible = isQuickMenuOpen,
            modifier = Modifier.padding(top = 80.dp, start = 16.dp, end = 16.dp).align(Alignment.TopCenter)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xEE161B26)), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Aspect Ratio", color = Color.Gray, fontSize = 11.sp)
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("1:1", "4:3", "16:9", "Full").forEach { asp ->
                            Button(
                                onClick = { selectedAspect = asp },
                                colors = ButtonDefaults.buttonColors(containerColor = if (selectedAspect == asp) Color(0xFFFFD60A) else Color(0xFF222938)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                            ) { Text(asp, color = if (selectedAspect == asp) Color.Black else Color.White, fontSize = 11.sp) }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Timer", color = Color.Gray, fontSize = 11.sp)
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf(0 to "Off", 3 to "3s", 5 to "5s", 10 to "10s").forEach { (sec, label) ->
                            Button(
                                onClick = { timerDuration = sec },
                                colors = ButtonDefaults.buttonColors(containerColor = if (timerDuration == sec) Color(0xFFFFD60A) else Color(0xFF222938)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                            ) { Text(label, color = if (timerDuration == sec) Color.Black else Color.White, fontSize = 11.sp) }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        QuickToggleButton("Grid #", isGridOn) { isGridOn = !isGridOn }
                        QuickToggleButton("Level ⊝", isLevelOn) { isLevelOn = !isLevelOn }
                        QuickToggleButton("Watermark", isWatermarkOn) { isWatermarkOn = !isWatermarkOn }
                    }
                }
            }
        }

        // MORE Modes Sheet
        AnimatedVisibility(visible = isMoreModesOpen, modifier = Modifier.fillMaxSize().background(Color(0xF20A0D14))) {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("All Camera Modes", color = Color.White, fontSize = 20.sp)
                    Button(onClick = { isMoreModesOpen = false }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222938))) { Text("Close ✕") }
                }
                Spacer(modifier = Modifier.height(20.dp))
                val modesList = listOf("EXPERT RAW", "PRO VIDEO", "NIGHT", "PORTRAIT", "SLOW MOTION", "TIME-LAPSE", "PANORAMA", "PRO")
                modesList.chunked(3).forEach { r ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        r.forEach { m ->
                            Button(
                                onClick = { selectedMode = m; isMoreModesOpen = false },
                                colors = ButtonDefaults.buttonColors(containerColor = if (selectedMode == m) Color(0xFFFFD60A) else Color(0xFF161B26)),
                                modifier = Modifier.size(95.dp, 65.dp)
                            ) { Text(m, color = if (selectedMode == m) Color.Black else Color.White, fontSize = 10.sp) }
                        }
                    }
                }
            }
        }

        // Countdown Display
        if (isCountingDown) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("$countdownValue", color = Color(0xFFFFD60A), fontSize = 95.sp)
            }
        }

        // Bottom Bar (Zoom, Modes, Shutter, Flip)
        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(modifier = Modifier.background(Color(0x88000000), RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(0.6f, 1f, 2f, 5f).forEach { z ->
                    Text(
                        "${z}x",
                        color = if (currentZoom == z) Color(0xFFFFD60A) else Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { currentZoom = z; camera?.cameraControl?.setZoomRatio(z) }.padding(3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("PHOTO", "VIDEO", "PORTRAIT", "NIGHT", "PRO").forEach { mode ->
                    Text(
                        mode,
                        color = if (selectedMode == mode) Color(0xFFFFD60A) else Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable { selectedMode = mode }.padding(4.dp)
                    )
                }
                Text("MORE ☰", color = Color(0xFF0A84FF), fontSize = 13.sp, modifier = Modifier.clickable { isMoreModesOpen = true }.padding(4.dp))
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(45.dp))

                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(6.dp)
                        .background(if (selectedMode == "VIDEO" || selectedMode == "PRO VIDEO") Color.Red else Color.White, CircleShape)
                        .clickable {
                            if (selectedMode == "VIDEO" || selectedMode == "PRO VIDEO") {
                                if (!isRecording) {
                                    videoCapture?.let {
                                        activeRecording = startRealVideoRecording(context, it) { isRecording = false }
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
                                        captureRealProcessedPhoto(context, imageCapture, selectedMode, portraitBlur, isWatermarkOn, selectedAspect)
                                    }
                                } else {
                                    captureRealProcessedPhoto(context, imageCapture, selectedMode, portraitBlur, isWatermarkOn, selectedAspect)
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
}

// -------------------------------------------------------------
// २. गॅलरीत 4K/1080p/720p व्हिडिओ सेव्ह करणारा खरा रेकॉर्डर
// -------------------------------------------------------------
fun startRealVideoRecording(
    context: Context,
    videoCapture: VideoCapture<Recorder>,
    onComplete: () -> Unit
): Recording {
    val name = "ProCam_Video_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}"
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
                Toast.makeText(context, "🔴 व्हिडिओ रेकॉर्डिंग सुरू झाले!", Toast.LENGTH_SHORT).show()
            }
            is VideoRecordEvent.Finalize -> {
                onComplete()
                if (!event.hasError()) {
                    Toast.makeText(context, "🎥 व्हिडिओ फोनच्या गॅलरीत सेव्ह झाला!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "रेकॉर्डिंग त्रुटी: ${event.error}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

// -------------------------------------------------------------
// ३. खरा फोटो सेव्ह करणे (Aspect Ratio, DSLR, Night, Watermark)
// -------------------------------------------------------------
fun captureRealProcessedPhoto(
    context: Context,
    capture: ImageCapture?,
    mode: String,
    blurLevel: Float,
    addWatermark: Boolean,
    aspectRatio: String
) {
    val imageCapture = capture ?: return
    val name = "ProCam_${mode}_${System.currentTimeMillis()}"
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ProCam AI")
        }
    }

    val outputOptions = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        contentValues
    ).build()

    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) {
                Toast.makeText(context, "फोटो क्लिक झाला नाही: ${exc.message}", Toast.LENGTH_SHORT).show()
            }

            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                output.savedUri?.let { uri ->
                    Thread {
                        try {
                            val stream = context.contentResolver.openInputStream(uri)
                            var bmp = BitmapFactory.decodeStream(stream)

                            // निवडलेल्या Aspect Ratio (1:1, 16:9) नुसार क्रॉप
                            bmp = cropToRealAspectRatio(bmp, aspectRatio)

                            // मोडनुसार खरा इफेक्ट
                            if (mode == "PORTRAIT") bmp = applyDSLRBokehEffect(bmp, blurLevel)
                            if (mode == "NIGHT") bmp = processNightPhoto(bmp)

                            // Shot on ProCam AI वॉटरमार्क
                            if (addWatermark) bmp = addBrandWatermark(bmp)

                            val outStream = context.contentResolver.openOutputStream(uri)
                            outStream?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                        } catch (_: Exception) {}
                    }.start()
                }
                Toast.makeText(context, "📸 फोटो $mode मोडमध्ये सेव्ह झाला!", Toast.LENGTH_SHORT).show()
            }
        }
    )
}

fun cropToRealAspectRatio(src: Bitmap, aspect: String): Bitmap {
    val w = src.width; val h = src.height
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
}// -------------------------------------------------------------
// ४. AI Photo Lab (Before/After Split Slider सह)
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
        Text("🤖 AI Photo Enhancement", color = Color.White, fontSize = 20.sp)
        Text("HD Clarity • Noise Reduction • Face Preserve", color = Color.Gray, fontSize = 12.sp)

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
                Text("✨ धूसर/जुना फोटो निवडण्यासाठी टॅप करा", color = Color(0xFF0A84FF))
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                modifier = Modifier.weight(1.5f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
            ) { Text("⚡ AI HD Enhance") }
        }

        if (enhancedBitmap != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Button(onClick = { enhancedBitmap?.let { saveBitmapDirectly(context, it, "ProCam_AI") } }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF))) {
                Text("💾 Save Enhanced HD Photo", color = Color.White)
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
// ५. सर्व ५ फिल्टर्स असलेला Photo Editor
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

        // ५ फिल्टर्सची पट्टी
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Natural", "Cinematic", "Vintage", "B&W", "Golden Hour").forEach { f ->
                Button(
                    onClick = { selectedFilter = f },
                    colors = ButtonDefaults.buttonColors(containerColor = if (selectedFilter == f) Color(0xFFFFD60A) else Color(0xFF1E2433))
                ) { Text(f, color = if (selectedFilter == f) Color.Black else Color.White, fontSize = 12.sp) }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { displayedBitmap?.let { saveBitmapDirectly(context, it, "ProCam_Edit") } }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))) {
            Text("💾 Save Edited Photo", color = Color.White)
        }
    }
}

// -------------------------------------------------------------
// ६. Settings & Navigation Bar
// -------------------------------------------------------------
@Composable
fun CompleteSettingsScreen() {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
        Text("⚙️ ProCam Settings", color = Color.White, fontSize = 22.sp)
        Spacer(modifier = Modifier.height(14.dp))
        Text("📸 Photo Quality: 100% High Resolution", color = Color.LightGray)
        Spacer(modifier = Modifier.height(8.dp))
        Text("🎥 Video: 4K UHD / 1080p FHD / 720p HD with Audio", color = Color.LightGray)
        Spacer(modifier = Modifier.height(8.dp))
        Text("📂 Save Location: Pictures/ProCam AI & Movies/ProCam AI", color = Color.LightGray)
        Spacer(modifier = Modifier.height(8.dp))
        Text("🌐 Language: मराठी / हिंदी / English", color = Color.LightGray)
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

@Composable
fun QuickToggleButton(label: String, isActive: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.background(if (isActive) Color(0xFFFFD60A) else Color(0xFF222938), RoundedCornerShape(8.dp)).clickable { onClick() }.padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(label, color = if (isActive) Color.Black else Color.White, fontSize = 11.sp)
    }
}

// -------------------------------------------------------------
// ७. इमेज अल्गोरिदम्स (Watermark, Bokeh, Night, AI, Filters)
// -------------------------------------------------------------
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
    canvas.drawText("⚡ SHOT ON PROCAM AI", 40f, res.height - 40f, p)
    return res
}

fun applyDSLRBokehEffect(src: Bitmap, blurRadius: Float): Bitmap {
    val w = src.width; val h = src.height
    val blurredBg = Bitmap.createScaledBitmap(src, (w / 4).coerceAtLeast(1), (h / 4).coerceAtLeast(1), true)
    val smoothBg = Bitmap.createScaledBitmap(blurredBg, w, h, true)
    val res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(res)
    canvas.drawBitmap(smoothBg, 0f, 0f, null)

    val shader = RadialGradient(w / 2f, h / 2f, w.coerceAtMost(h) * 0.45f, intArrayOf(android.graphics.Color.WHITE, android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT), floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP)
    val maskPaint = Paint().apply { setShader(shader); xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    val sharp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val sc = Canvas(sharp)
    sc.drawBitmap(src, 0f, 0f, null)
    sc.drawRect(0f, 0f, w.toFloat(), h.toFloat(), maskPaint)
    canvas.drawBitmap(sharp, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
    return res
}

fun processNightPhoto(src: Bitmap): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(res)
    val matrix = ColorMatrix(floatArrayOf(
        1.25f, 0f, 0f, 0f, 25f,
        0f, 1.25f, 0f, 0f, 25f,
        0f, 0f, 1.30f, 0f, 20f,
        0f, 0f, 0f, 1f, 0f
    ))
    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix); isAntiAlias = true })
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

fun saveBitmapDirectly(context: Context, bitmap: Bitmap, prefix: String) {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "${prefix}_${System.currentTimeMillis()}.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ProCam AI")
        }
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    uri?.let {
        val stream: OutputStream? = context.contentResolver.openOutputStream(it)
        stream?.use { s ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, s)
            Toast.makeText(context, "✅ फोटो गॅलरीत सेव्ह झाला!", Toast.LENGTH_SHORT).show()
        }
    }
}
