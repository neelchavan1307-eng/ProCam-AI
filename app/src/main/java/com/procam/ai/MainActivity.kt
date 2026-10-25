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
                ProCamFlagshipApp()
            }
        }
    }
}

@Composable
fun ProCamFlagshipApp() {
    var currentScreen by remember { mutableStateOf("CAMERA") }

    Scaffold(
        bottomBar = {
            BottomNavBar(currentScreen = currentScreen, onTabSelected = { currentScreen = it })
        },
        containerColor = Color(0xFF0A0D14)
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (currentScreen) {
                "CAMERA" -> FlagshipCameraScreen()
                "EDITOR" -> RealEditorScreen()
                "AI_LAB" -> RealAILabScreen()
                "SETTINGS" -> RealSettingsScreen()
            }
        }
    }
}

// -------------------------------------------------------------
// १. फ्लॅगशिप कॅमेरा (Quick Dropdown, Level Line, More Modes)
// -------------------------------------------------------------
@Composable
fun FlagshipCameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // कॅमेरा व हार्डवेअर स्टेट
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var isFlashOn by remember { mutableStateOf(false) }
    var currentZoom by remember { mutableFloatStateOf(1f) }
    var selectedMode by remember { mutableStateOf("PHOTO") }

    // नवीन ॲडव्हान्स फीचर्स (स्क्रीनशॉट्समधील)
    var isQuickMenuOpen by remember { mutableStateOf(false) }
    var isMoreModesOpen by remember { mutableStateOf(false) }
    var selectedAspect by remember { mutableStateOf("4:3") }
    var timerDuration by remember { mutableIntStateOf(0) }
    var isGridOn by remember { mutableStateOf(true) }
    var isLevelOn by remember { mutableStateOf(true) }
    var isWatermarkOn by remember { mutableStateOf(true) }
    var isHDROn by remember { mutableStateOf(true) }
    var countdownValue by remember { mutableIntStateOf(0) }
    var isCountingDown by remember { mutableStateOf(false) }

    // Pro व Portrait नियंत्रणे
    var portraitBlur by remember { mutableFloatStateOf(0.6f) }
    var exposureComp by remember { mutableFloatStateOf(0f) }

    val previewView = remember { PreviewView(context) }
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(hasPermission, lensFacing, selectedMode) {
        if (hasPermission) {
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
            imageCapture = capture

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.Builder().requireLensFacing(lensFacing).build(), preview, capture)
                camera?.cameraControl?.setZoomRatio(currentZoom)
                if (selectedMode == "NIGHT") camera?.cameraControl?.setExposureCompensationIndex(2)
            } catch (e: Exception) {
                Toast.makeText(context, "कॅमेरा त्रुटी: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        }

        // २. ३x३ Grid Lines
        if (isGridOn) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val c = Color(0x44FFFFFF)
                drawLine(c, Offset(w / 3, 0f), Offset(w / 3, h), 1.dp.toPx())
                drawLine(c, Offset(w * 2 / 3, 0f), Offset(w * 2 / 3, h), 1.dp.toPx())
                drawLine(c, Offset(0f, h / 3), Offset(w, h / 3), 1.dp.toPx())
                drawLine(c, Offset(0f, h * 2 / 3), Offset(w, h * 2 / 3), 1.dp.toPx())
            }
        }

        // ३. Horizon Level Indicator (कॅमेरा सरळ ठेवण्यासाठी पिवळी लेव्हल रेषा)
        if (isLevelOn) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val midY = h / 2
                // मध्यभागी सरळ पिवळी रेषा
                drawLine(Color(0xFFFFD60A), Offset(w * 0.25f, midY), Offset(w * 0.75f, midY), 1.5.dp.toPx())
                // मध्य बिंदू
                drawCircle(Color(0xFFFFD60A), radius = 3.dp.toPx(), center = Offset(w / 2, midY))
            }
        }

        // ४. Top Pro Parameter Pill Bar (ISO, Shutter, EV, 4K)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Flash
            IconButton(
                onClick = { isFlashOn = !isFlashOn; camera?.cameraControl?.enableTorch(isFlashOn) },
                modifier = Modifier.size(38.dp).background(if (isFlashOn) Color(0xFFFFD60A) else Color(0x66000000), CircleShape)
            ) {
                Text(if (isFlashOn) "⚡" else "⚡", color = if (isFlashOn) Color.Black else Color.White, fontSize = 13.sp)
            }

            // Live Parameters Pill
            Row(
                modifier = Modifier
                    .background(Color(0x88000000), RoundedCornerShape(20.dp))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("ISO 400", color = Color(0xFFFFD60A), fontSize = 11.sp)
                Text("1/125s", color = Color.White, fontSize = 11.sp)
                Text("EV ${exposureComp.toInt()}", color = Color.LightGray, fontSize = 11.sp)
                Text(selectedAspect, color = Color(0xFF0A84FF), fontSize = 11.sp)
            }

            // Quick Settings Toggle Button (ड्रॉपडाउन उघडणे)
            IconButton(
                onClick = { isQuickMenuOpen = !isQuickMenuOpen },
                modifier = Modifier.size(38.dp).background(if (isQuickMenuOpen) Color(0xFFFFD60A) else Color(0x66000000), CircleShape)
            ) {
                Text("⚙️", fontSize = 14.sp)
            }
        }

        // ५. Quick Settings Dropdown Overlay (स्क्रीनशॉट १ व ४ प्रमाणे)
        AnimatedVisibility(
            visible = isQuickMenuOpen,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
            modifier = Modifier.padding(top = 80.dp, start = 16.dp, end = 16.dp).align(Alignment.TopCenter)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xEE161B26)),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2C3240))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Aspect Ratio Row
                    Text("Aspect Ratio", color = Color.Gray, fontSize = 11.sp)
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("1:1", "4:3", "16:9", "Full").forEach { asp ->
                            Button(
                                onClick = { selectedAspect = asp },
                                colors = ButtonDefaults.buttonColors(containerColor = if (selectedAspect == asp) Color(0xFFFFD60A) else Color(0xFF222938)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                            ) {
                                Text(asp, color = if (selectedAspect == asp) Color.Black else Color.White, fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Timer Row
                    Text("Timer", color = Color.Gray, fontSize = 11.sp)
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf(0 to "Off", 3 to "3s", 5 to "5s", 10 to "10s").forEach { (sec, label) ->
                            Button(
                                onClick = { timerDuration = sec },
                                colors = ButtonDefaults.buttonColors(containerColor = if (timerDuration == sec) Color(0xFFFFD60A) else Color(0xFF222938)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                            ) {
                                Text(label, color = if (timerDuration == sec) Color.Black else Color.White, fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Grid, Level, Watermark, HDR Toggles
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        QuickToggleButton("Grid #", isGridOn) { isGridOn = !isGridOn }
                        QuickToggleButton("Level ⊝", isLevelOn) { isLevelOn = !isLevelOn }
                        QuickToggleButton("Watermark", isWatermarkOn) { isWatermarkOn = !isWatermarkOn }
                        QuickToggleButton("HDR", isHDROn) { isHDROn = !isHDROn }
                    }
                }
            }
        }

        // ६. "MORE" Full Modes Grid Overlay (स्क्रीनशॉट २ व ५ प्रमाणे)
        AnimatedVisibility(
            visible = isMoreModesOpen,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.fillMaxSize().background(Color(0xF00A0D14))
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Camera Modes", color = Color.White, fontSize = 20.sp)
                    Button(onClick = { isMoreModesOpen = false }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222938))) {
                        Text("Close ✕")
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))

                val allModes = listOf(
                    "EXPERT RAW" to "RAW", "PRO VIDEO" to "🎬", "NIGHT" to "🌙",
                    "PORTRAIT" to "👤", "SLOW MOTION" to "⏱", "TIME-LAPSE" to "⚡",
                    "PANORAMA" to "🌄", "TILT-SHIFT" to "🔍", "FOOD" to "🍲", "PRO" to "🎛"
                )

                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    allModes.chunked(3).forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { (modeTitle, icon) ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .size(90.dp)
                                        .background(if (selectedMode == modeTitle) Color(0xFFFFD60A) else Color(0xFF161B26), RoundedCornerShape(16.dp))
                                        .clickable {
                                            selectedMode = modeTitle
                                            isMoreModesOpen = false
                                        }
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(icon, fontSize = 24.sp)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(modeTitle, color = if (selectedMode == modeTitle) Color.Black else Color.White, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ७. Countdown Overlay
        if (isCountingDown) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("$countdownValue", color = Color(0xFFFFD60A), fontSize = 90.sp)
            }
        }

        // ८. Bottom Controls (Zoom, Modes, Shutter, Flip)
        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Zoom Slider (0.6x, 1x, 2x, 5x, 10x - स्क्रीनशॉट ७ प्रमाणे)
            Row(
                modifier = Modifier.background(Color(0x88000000), RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                listOf(0.6f, 1f, 2f, 3f, 5f).forEach { z ->
                    Text(
                        if (z < 1f) "${z}x" else "${z.toInt()}x",
                        color = if (currentZoom == z) Color(0xFFFFD60A) else Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { currentZoom = z; camera?.cameraControl?.setZoomRatio(z) }.padding(3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Modes Strip + MORE Button
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
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

            // Shutter Row
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(45.dp)) // Spacer

                // Shutter Button
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(6.dp)
                        .background(if (selectedMode == "VIDEO" || selectedMode == "PRO VIDEO") Color.Red else Color.White, CircleShape)
                        .clickable {
                            if (timerDuration > 0) {
                                scope.launch {
                                    isCountingDown = true
                                    for (i in timerDuration downTo 1) {
                                        countdownValue = i
                                        delay(1000)
                                    }
                                    isCountingDown = false
                                    takePhotoWithMode(context, imageCapture, selectedMode, portraitBlur, isWatermarkOn)
                                }
                            } else {
                                takePhotoWithMode(context, imageCapture, selectedMode, portraitBlur, isWatermarkOn)
                            }
                        }
                )

                // Flip Camera
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

@Composable
fun QuickToggleButton(label: String, isActive: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(if (isActive) Color(0xFFFFD60A) else Color(0xFF222938), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(label, color = if (isActive) Color.Black else Color.White, fontSize = 11.sp)
    }
}// -------------------------------------------------------------
// २. AI Photo Lab (Before/After Split Slider सह)
// -------------------------------------------------------------
@Composable
fun RealAILabScreen() {
    val context = LocalContext.current
    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var enhancedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var sliderRatio by remember { mutableFloatStateOf(0.5f) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val stream = context.contentResolver.openInputStream(it)
            val loaded = BitmapFactory.decodeStream(stream)
            val scaled = Bitmap.createScaledBitmap(loaded, 1080, (1080f * loaded.height / loaded.width).toInt(), true)
            originalBitmap = scaled
            enhancedBitmap = null
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🤖 AI Photo Enhancement", color = Color.White, fontSize = 20.sp)
        Text("HD Clarity • Noise Reduction • Face Preserve", color = Color.Gray, fontSize = 12.sp)

        Spacer(modifier = Modifier.height(14.dp))

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
                Text("✨ धूसर/जुना फोटो निवडण्यासाठी इथे टॅप करा", color = Color(0xFF0A84FF), fontSize = 14.sp)
            }

            if (isProcessing) {
                Box(modifier = Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFFFFD60A))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("AI फोटो सुधारत आहे...", color = Color.White)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222938))) {
                Text("नवीन फोटो")
            }
            Button(
                onClick = {
                    val src = originalBitmap
                    if (src != null) {
                        isProcessing = true
                        Thread {
                            val res = applyAIEnhancement(src)
                            (context as? android.app.Activity)?.runOnUiThread {
                                enhancedBitmap = res
                                isProcessing = false
                            }
                        }.start()
                    }
                },
                modifier = Modifier.weight(1.5f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
            ) {
                Text("⚡ AI HD Enhance")
            }
        }

        if (enhancedBitmap != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { enhancedBitmap?.let { saveBitmapDirectly(context, it, "ProCam_AI_HD") } },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF))
            ) {
                Text("💾 Save Enhanced HD Photo", color = Color.White)
            }
        }
    }
}

@Composable
fun BeforeAfterSplitView(before: Bitmap, after: Bitmap, splitRatio: Float, onRatioChange: (Float) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight

        Image(bitmap = after.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)

        Box(modifier = Modifier.fillMaxHeight().width(w * splitRatio).clipToBounds()) {
            Image(bitmap = before.asImageBitmap(), contentDescription = null, modifier = Modifier.size(w, h), contentScale = ContentScale.Fit)
        }

        Box(modifier = Modifier.fillMaxHeight().width(3.dp).offset(x = w * splitRatio).background(Color.White))

        Text("BEFORE", color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0x88000000), RoundedCornerShape(4.dp)).padding(4.dp))
        Text("AFTER (AI)", color = Color(0xFFFFD60A), fontSize = 11.sp, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color(0x88000000), RoundedCornerShape(4.dp)).padding(4.dp))

        Slider(
            value = splitRatio,
            onValueChange = onRatioChange,
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 6.dp),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent)
        )
    }
}

// -------------------------------------------------------------
// ३. Professional Photo Editor Screen
// -------------------------------------------------------------
@Composable
fun RealEditorScreen() {
    val context = LocalContext.current
    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var displayedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var selectedFilter by remember { mutableStateOf("Natural") }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val stream = context.contentResolver.openInputStream(it)
            val loaded = BitmapFactory.decodeStream(stream)
            val scaled = Bitmap.createScaledBitmap(loaded, 1080, (1080f * loaded.height / loaded.width).toInt(), true)
            originalBitmap = scaled
            displayedBitmap = scaled
        }
    }

    LaunchedEffect(brightness, contrast, selectedFilter, originalBitmap) {
        originalBitmap?.let {
            displayedBitmap = applyFilters(it, brightness, contrast, selectedFilter)
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🎨 Professional Editor", color = Color.White, fontSize = 20.sp)

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF161B26)).clickable { galleryLauncher.launch("image/*") },
            contentAlignment = Alignment.Center
        ) {
            val bmp = displayedBitmap
            if (bmp != null) {
                Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                Text("📁 गॅलरीतून फोटो निवडण्यासाठी टॅप करा", color = Color(0xFF0A84FF), fontSize = 15.sp)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text("Brightness: ${brightness.toInt()}", color = Color.LightGray, fontSize = 12.sp)
        Slider(value = brightness, onValueChange = { brightness = it }, valueRange = -100f..100f)

        Text("Contrast: ${String.format("%.1fx", contrast)}", color = Color.LightGray, fontSize = 12.sp)
        Slider(value = contrast, onValueChange = { contrast = it }, valueRange = 0.5f..2f)

        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Natural", "Cinematic", "Vintage", "B&W", "Golden Hour").forEach { f ->
                Button(
                    onClick = { selectedFilter = f },
                    colors = ButtonDefaults.buttonColors(containerColor = if (selectedFilter == f) Color(0xFFFFD60A) else Color(0xFF1E2433))
                ) {
                    Text(f, color = if (selectedFilter == f) Color.Black else Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { displayedBitmap?.let { saveBitmapDirectly(context, it, "ProCam_Edit") } },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
        ) {
            Text("💾 Save Edited Photo", color = Color.White)
        }
    }
}

// -------------------------------------------------------------
// ४. Settings Screen आणि Bottom Bar
// -------------------------------------------------------------
@Composable
fun RealSettingsScreen() {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
        Text("⚙️ ProCam Settings", color = Color.White, fontSize = 22.sp)
        Spacer(modifier = Modifier.height(20.dp))
        Text("📸 Photo Quality: High (100% RAW/JPEG)", color = Color.LightGray)
        Spacer(modifier = Modifier.height(12.dp))
        Text("🎥 Video: 4K 60FPS Supported", color = Color.LightGray)
        Spacer(modifier = Modifier.height(12.dp))
        Text("📐 Aspect Ratio: 4:3 / 16:9 / Full", color = Color.LightGray)
        Spacer(modifier = Modifier.height(12.dp))
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

// -------------------------------------------------------------
// ५. फोटो सेव्हिंग आणि वॉटरमार्क लॉजिक (Shot on ProCam AI)
// -------------------------------------------------------------
fun takePhotoWithMode(context: Context, capture: ImageCapture?, mode: String, blurLevel: Float, addWatermark: Boolean) {
    val imageCapture = capture ?: return

    val fileName = "ProCam_${mode}_${System.currentTimeMillis()}"
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
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

                            // मोडनुसार इफेक्ट
                            if (mode == "PORTRAIT") bmp = applyDSLRBokehEffect(bmp, blurLevel)
                            if (mode == "NIGHT") bmp = processNightPhoto(bmp)

                            // वॉटरमार्क जोडणे (स्क्रीनशॉट १ प्रमाणे)
                            if (addWatermark) {
                                bmp = addBrandWatermark(bmp)
                            }

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

// -------------------------------------------------------------
// ६. इमेज प्रोसेसिंग अल्गोरिदम (Watermark, Bokeh, Night, AI, Filters)
// -------------------------------------------------------------
fun addBrandWatermark(src: Bitmap): Bitmap {
    val result = src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(result)
    val paint = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = (result.width * 0.035f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
        isAntiAlias = true
    }
    // डाव्या कोपऱ्यात ब्रँडिंग
    canvas.drawText("⚡ SHOT ON PROCAM AI", 40f, result.height - 40f, paint)
    return result
}

fun applyDSLRBokehEffect(src: Bitmap, blurRadius: Float): Bitmap {
    val w = src.width
    val h = src.height
    val blurredBg = Bitmap.createScaledBitmap(src, (w / 4).coerceAtLeast(1), (h / 4).coerceAtLeast(1), true)
    val smoothBg = Bitmap.createScaledBitmap(blurredBg, w, h, true)

    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    canvas.drawBitmap(smoothBg, 0f, 0f, null)

    val focusRadius = (w.coerceAtMost(h) * 0.45f)
    val shader = RadialGradient(
        w / 2f, h / 2f, focusRadius,
        intArrayOf(android.graphics.Color.WHITE, android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT),
        floatArrayOf(0f, 0.65f, 1f),
        Shader.TileMode.CLAMP
    )
    val maskPaint = Paint().apply { setShader(shader); xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    val sharpSubject = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val subjectCanvas = Canvas(sharpSubject)
    subjectCanvas.drawBitmap(src, 0f, 0f, null)
    subjectCanvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), maskPaint)

    canvas.drawBitmap(sharpSubject, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
    return result
}

fun processNightPhoto(src: Bitmap): Bitmap {
    val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val matrix = ColorMatrix(floatArrayOf(
        1.25f, 0f, 0f, 0f, 25f,
        0f, 1.25f, 0f, 0f, 25f,
        0f, 0f, 1.30f, 0f, 20f,
        0f, 0f, 0f, 1f, 0f
    ))
    val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix); isAntiAlias = true }
    canvas.drawBitmap(src, 0f, 0f, paint)
    return result
}

fun applyAIEnhancement(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    val contrastBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(contrastBmp)
    val cm = ColorMatrix(floatArrayOf(
        1.18f, 0f, 0f, 0f, 8f,
        0f, 1.18f, 0f, 0f, 8f,
        0f, 0f, 1.15f, 0f, 6f,
        0f, 0f, 0f, 1f, 0f
    ))
    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm); isAntiAlias = true })

    val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(w * h)
    val outPixels = IntArray(w * h)
    contrastBmp.getPixels(pixels, 0, w, 0, 0, w, h)

    for (y in 1 until h - 1) {
        for (x in 1 until w - 1) {
            val idx = y * w + x
            var r = 0; var g = 0; var b = 0
            val neighbors = intArrayOf(idx - w, idx - 1, idx, idx + 1, idx + w)
            val weights = intArrayOf(-1, -1, 5, -1, -1)
            for (i in neighbors.indices) {
                val p = pixels[neighbors[i]]
                val weight = weights[i]
                r += ((p shr 16) and 0xFF) * weight
                g += ((p shr 8) and 0xFF) * weight
                b += (p and 0xFF) * weight
            }
            outPixels[idx] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
    }
    output.setPixels(outPixels, 0, w, 0, 0, w, h)
    return output
}

fun applyFilters(src: Bitmap, brightness: Float, contrast: Float, filter: String): Bitmap {
    val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val matrix = ColorMatrix()
    matrix.postConcat(ColorMatrix(floatArrayOf(
        contrast, 0f, 0f, 0f, brightness,
        0f, contrast, 0f, 0f, brightness,
        0f, 0f, contrast, 0f, brightness,
        0f, 0f, 0f, 1f, 0f
    )))
    when (filter) {
        "B&W" -> { val bw = ColorMatrix(); bw.setSaturation(0f); matrix.postConcat(bw) }
        "Vintage" -> matrix.postConcat(ColorMatrix(floatArrayOf(0.9f, 0f, 0f, 0f, 20f, 0f, 0.75f, 0f, 0f, 10f, 0f, 0f, 0.6f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        "Cinematic" -> matrix.postConcat(ColorMatrix(floatArrayOf(1.1f, 0f, 0f, 0f, 10f, 0f, 1.0f, 0f, 0f, 5f, 0f, 0f, 1.25f, 0f, 15f, 0f, 0f, 0f, 1f, 0f)))
        "Golden Hour" -> matrix.postConcat(ColorMatrix(floatArrayOf(1.2f, 0f, 0f, 0f, 30f, 0f, 1.05f, 0f, 0f, 15f, 0f, 0f, 0.75f, 0f, -10f, 0f, 0f, 0f, 1f, 0f)))
    }
    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix); isAntiAlias = true })
    return result
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
