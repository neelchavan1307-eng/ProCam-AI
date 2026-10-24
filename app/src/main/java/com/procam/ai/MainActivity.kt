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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0D14)) {
                ProCamMainApp()
            }
        }
    }
}

@Composable
fun ProCamMainApp() {
    var currentScreen by remember { mutableStateOf("CAMERA") }

    Scaffold(
        bottomBar = {
            BottomNavBar(currentScreen = currentScreen, onTabSelected = { currentScreen = it })
        },
        containerColor = Color(0xFF0A0D14)
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (currentScreen) {
                "CAMERA" -> RealCameraScreen()
                "EDITOR" -> RealEditorScreen()
                "AI_LAB" -> RealAILabScreen()
                "SETTINGS" -> RealSettingsScreen()
            }
        }
    }
}

// -------------------------------------------------------------
// १. खरा कार्यरत कॅमेरा (Live Hardware Camera Screen)
// -------------------------------------------------------------
@Composable
fun RealCameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var isFlashOn by remember { mutableStateOf(false) }
    var currentZoom by remember { mutableFloatStateOf(1f) }
    var exposureCompensation by remember { mutableFloatStateOf(0f) }
    var selectedMode by remember { mutableStateOf("PHOTO") }

    val previewView = remember { PreviewView(context) }

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(hasPermission, lensFacing) {
        if (hasPermission) {
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            imageCapture = capture

            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                camera?.cameraControl?.setZoomRatio(currentZoom)
            } catch (e: Exception) {
                Toast.makeText(context, "कॅमेरा एरर: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        }

        // वरची पट्टी (Flash, Header, Resolution)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Flash बटण (खरा टॉर्च चालू/बंद करणे)
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(if (isFlashOn) Color(0xFFFFD60A) else Color(0x66000000), CircleShape)
                    .clickable {
                        isFlashOn = !isFlashOn
                        camera?.cameraControl?.enableTorch(isFlashOn)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(if (isFlashOn) "⚡ ON" else "⚡", color = if (isFlashOn) Color.Black else Color.White, fontSize = 13.sp)
            }

            Text("ProCam AI", color = Color.White, fontSize = 18.sp)

            Text("12MP 4K", color = Color(0xFFFFD60A), fontSize = 12.sp, modifier = Modifier.background(Color(0x66000000), RoundedCornerShape(6.dp)).padding(6.dp))
        }

        // Pro Mode मध्ये Exposure / Brightness कंट्रोल
        if (selectedMode == "PRO") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterEnd)
                    .padding(horizontal = 24.dp)
            ) {
                Text("Exposure: ${exposureCompensation.toInt()}", color = Color(0xFFFFD60A), fontSize = 12.sp)
                Slider(
                    value = exposureCompensation,
                    onValueChange = {
                        exposureCompensation = it
                        camera?.cameraControl?.setExposureCompensationIndex(it.toInt())
                    },
                    valueRange = -4f..4f,
                    steps = 8,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFFFD60A), activeTrackColor = Color(0xFFFFD60A))
                )
            }
        }

        // खालची कॅमेरा नियंत्रणे
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Zoom बटणे (1x, 2x, 3x)
            Row(
                modifier = Modifier
                    .background(Color(0x77000000), RoundedCornerShape(20.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                listOf(1f, 2f, 3f).forEach { zoomLevel ->
                    val isSelected = currentZoom == zoomLevel
                    Text(
                        text = "${zoomLevel.toInt()}x",
                        color = if (isSelected) Color(0xFFFFD60A) else Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable {
                                currentZoom = zoomLevel
                                camera?.cameraControl?.setZoomRatio(zoomLevel)
                            }
                            .padding(4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Modes
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                listOf("PRO", "PORTRAIT", "PHOTO", "VIDEO").forEach { mode ->
                    Text(
                        text = mode,
                        color = if (selectedMode == mode) Color(0xFFFFD60A) else Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clickable { selectedMode = mode }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Shutter Row (फोटो काढणे आणि कॅमेरा फिरवणे)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(45.dp))

                // मुख्य Shutter बटण
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(6.dp)
                        .background(if (selectedMode == "VIDEO") Color.Red else Color.White, CircleShape)
                        .clickable {
                            takeRealPhoto(context, imageCapture)
                        }
                )

                // Flip Camera बटण (Front/Back कॅमेरा बदलणे)
                Box(
                    modifier = Modifier
                        .size(45.dp)
                        .background(Color(0x66000000), CircleShape)
                        .clickable {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("🔄", fontSize = 20.sp)
                }
            }
        }
    }
}// -------------------------------------------------------------
// २. खरा कार्यरत एडिटर (Real Working Photo Editor)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🎨 ProCam Photo Editor", color = Color.White, fontSize = 20.sp)

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF161B26))
                .clickable { galleryLauncher.launch("image/*") },
            contentAlignment = Alignment.Center
        ) {
            val bmp = displayedBitmap
            if (bmp != null) {
                Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                Text("📁 गॅलरीतून फोटो निवडण्यासाठी इथे टॅप करा", color = Color(0xFF0A84FF), fontSize = 15.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text("Brightness: ${brightness.toInt()}", color = Color.LightGray, fontSize = 12.sp)
        Slider(value = brightness, onValueChange = { brightness = it }, valueRange = -100f..100f)

        Text("Contrast: ${String.format("%.1fx", contrast)}", color = Color.LightGray, fontSize = 12.sp)
        Slider(value = contrast, onValueChange = { contrast = it }, valueRange = 0.5f..2f)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
            onClick = {
                displayedBitmap?.let { saveToGallery(context, it) } ?: Toast.makeText(context, "आधी फोटो निवडा", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
        ) {
            Text("💾 Save Edited Photo", color = Color.White)
        }
    }
}

// -------------------------------------------------------------
// ३. AI Lab स्क्रीन
// -------------------------------------------------------------
@Composable
fun RealAILabScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("✨ AI Photo Lab", color = Color.White, fontSize = 20.sp)
        Spacer(modifier = Modifier.height(20.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1F2C))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("1-Tap AI HD Enhancement", color = Color(0xFFFFD60A), fontSize = 16.sp)
                Text("कमी प्रकाशातील फोटोंचा नॉईज कमी करून चेहरा नैसर्गिक ठेवतो (Face Preserve).", color = Color.LightGray, fontSize = 13.sp)
            }
        }
    }
}

// -------------------------------------------------------------
// ४. सेटिंग्ज स्क्रीन
// -------------------------------------------------------------
@Composable
fun RealSettingsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        Text("⚙️ ProCam Settings", color = Color.White, fontSize = 20.sp)
        Spacer(modifier = Modifier.height(20.dp))
        Text("Photo Quality: High (JPEG 100%)", color = Color.LightGray)
        Spacer(modifier = Modifier.height(10.dp))
        Text("Video: 4K 60FPS Supported Hardware", color = Color.LightGray)
        Spacer(modifier = Modifier.height(10.dp))
        Text("Language: मराठी / English", color = Color.LightGray)
    }
}

// -------------------------------------------------------------
// ५. Bottom Navigation Bar
// -------------------------------------------------------------
@Composable
fun BottomNavBar(currentScreen: String, onTabSelected: (String) -> Unit) {
    NavigationBar(containerColor = Color(0xFF0F141C)) {
        NavigationBarItem(
            selected = currentScreen == "CAMERA",
            onClick = { onTabSelected("CAMERA") },
            icon = { Text("📷") },
            label = { Text("कॅमेरा", fontSize = 11.sp) }
        )
        NavigationBarItem(
            selected = currentScreen == "EDITOR",
            onClick = { onTabSelected("EDITOR") },
            icon = { Text("🎨") },
            label = { Text("एडिटर", fontSize = 11.sp) }
        )
        NavigationBarItem(
            selected = currentScreen == "AI_LAB",
            onClick = { onTabSelected("AI_LAB") },
            icon = { Text("✨") },
            label = { Text("AI Lab", fontSize = 11.sp) }
        )
        NavigationBarItem(
            selected = currentScreen == "SETTINGS",
            onClick = { onTabSelected("SETTINGS") },
            icon = { Text("⚙️") },
            label = { Text("सेटिंग्ज", fontSize = 11.sp) }
        )
    }
}

// -------------------------------------------------------------
// ६. प्रत्यक्ष फोटो कॅप्चर करणे (Real Image Capture)
// -------------------------------------------------------------
fun takeRealPhoto(context: Context, capture: ImageCapture?) {
    val imageCapture = capture ?: return

    val fileName = "ProCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
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
                Toast.makeText(context, "फोटो निघाला नाही: ${exc.message}", Toast.LENGTH_SHORT).show()
            }

            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                Toast.makeText(context, "📸 फोटो गॅलरीत सेव्ह झाला!", Toast.LENGTH_SHORT).show()
            }
        }
    )
}

// -------------------------------------------------------------
// ७. इमेज प्रोसेसिंग आणि सेव्हिंग
// -------------------------------------------------------------
fun applyFilters(src: Bitmap, brightness: Float, contrast: Float, filter: String): Bitmap {
    val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val matrix = ColorMatrix()

    val scale = contrast
    val translate = brightness
    matrix.postConcat(ColorMatrix(floatArrayOf(
        scale, 0f, 0f, 0f, translate,
        0f, scale, 0f, 0f, translate,
        0f, 0f, scale, 0f, translate,
        0f, 0f, 0f, 1f, 0f
    )))

    when (filter) {
        "B&W" -> { val bw = ColorMatrix(); bw.setSaturation(0f); matrix.postConcat(bw) }
        "Vintage" -> matrix.postConcat(ColorMatrix(floatArrayOf(0.9f, 0f, 0f, 0f, 20f, 0f, 0.75f, 0f, 0f, 10f, 0f, 0f, 0.6f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        "Cinematic" -> matrix.postConcat(ColorMatrix(floatArrayOf(1.1f, 0f, 0f, 0f, 10f, 0f, 1.0f, 0f, 0f, 5f, 0f, 0f, 1.25f, 0f, 15f, 0f, 0f, 0f, 1f, 0f)))
        "Golden Hour" -> matrix.postConcat(ColorMatrix(floatArrayOf(1.2f, 0f, 0f, 0f, 30f, 0f, 1.05f, 0f, 0f, 15f, 0f, 0f, 0.75f, 0f, -10f, 0f, 0f, 0f, 1f, 0f)))
    }

    val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix); isAntiAlias = true }
    canvas.drawBitmap(src, 0f, 0f, paint)
    return result
}

fun saveToGallery(context: Context, bitmap: Bitmap) {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "ProCam_Edit_${System.currentTimeMillis()}.jpg")
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
            Toast.makeText(context, "✅ फोटो सेव्ह झाला!", Toast.LENGTH_SHORT).show()
        }
    }
}
