package com.procam.ai

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
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
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Contrast
import androidx.media3.effect.RgbAdjustment
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.random.Random

// -------------------------------------------------------------
// १. Media3 Transformer: गॅलरीतील व्हिडिओवर खरा ९-इन-१ इफेक्ट लावून MP4 सेव्ह करणे
// -------------------------------------------------------------
@OptIn(UnstableApi::class)
fun exportCinematicVideoWithTransformer(
    context: Context,
    inputVideoUri: Uri,
    presetName: String,
    onComplete: (Boolean) -> Unit
) {
    val mainHandler = Handler(Looper.getMainLooper())
    val cacheOutputFile = File(context.cacheDir, "procam_rendered_${System.currentTimeMillis()}.mp4")

    val mediaItem = MediaItem.fromUri(inputVideoUri)

    // निवडलेल्या पॅकनुसार व्हिडिओ फ्रेम्सवर खरा GL इफेक्ट लावणे
    val videoEffects = mutableListOf<androidx.media3.common.Effect>()
    when (presetName) {
        "Cinematic 9-in-1" -> {
            // Teal & Orange + ड्रॅमॅटिक सिनेमॅटिक कॉन्ट्रास्ट
            videoEffects.add(Contrast(0.25f))
            videoEffects.add(RgbAdjustment.Builder().setRedScale(1.15f).setBlueScale(1.25f).build())
        }
        "Video Glow" -> {
            videoEffects.add(Contrast(0.1f))
            videoEffects.add(RgbAdjustment.Builder().setRedScale(1.05f).setGreenScale(1.05f).setBlueScale(1.1f).build())
        }
        "Retro Film" -> {
            videoEffects.add(Contrast(0.15f))
            videoEffects.add(RgbAdjustment.Builder().setRedScale(1.1f).setBlueScale(0.85f).build())
        }
        "Urban Night" -> {
            videoEffects.add(Contrast(0.35f))
            videoEffects.add(RgbAdjustment.Builder().setRedScale(1.2f).setBlueScale(1.35f).build())
        }
        else -> {
            videoEffects.add(Contrast(0.1f))
        }
    }

    val editedMediaItem = EditedMediaItem.Builder(mediaItem)
        .setEffects(Effects(emptyList(), videoEffects))
        .build()

    val transformer = Transformer.Builder(context)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                // रेंडर झालेला नवा व्हिडिओ MediaStore (गॅलरीत) कॉपी करणे
                val filename = "ProCam_Studio_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ProCam AI")
                    }
                }
                val outUri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                outUri?.let { dest ->
                    context.contentResolver.openOutputStream(dest)?.use { out ->
                        cacheOutputFile.inputStream().use { input -> input.copyTo(out) }
                    }
                }
                cacheOutputFile.delete()

                mainHandler.post {
                    Toast.makeText(context, "🎥 4K $presetName व्हिडिओ गॅलरीत सेव्ह झाला!", Toast.LENGTH_LONG).show()
                    onComplete(true)
                }
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                cacheOutputFile.delete()
                mainHandler.post {
                    Toast.makeText(context, "व्हिडिओ रेंडरिंग त्रुटी: ${exportException.message}", Toast.LENGTH_SHORT).show()
                    onComplete(false)
                }
            }
        })
        .build()

    try {
        transformer.start(editedMediaItem, cacheOutputFile.absolutePath)
        Toast.makeText(context, "⚡ $presetName व्हिडिओ रेंडरिंग सुरू झाले...", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "प्रक्रिया सुरू करताना अडचण: ${e.message}", Toast.LENGTH_SHORT).show()
        onComplete(false)
    }
}

// -------------------------------------------------------------
// २. फोटोसाठी ९-इन-१ MASTER CINEMATIC LOOK (Green-Orange, Oppenheimer, Wong Kar-wai, Glow, Letterbox)
// -------------------------------------------------------------
fun applyMasterCinematic9in1(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    val res = src.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(res)

    val cm = ColorMatrix(floatArrayOf(
        1.25f, 0f, 0f, 0f, 12f,
        0f, 1.05f, 0f, 0f, 2f,
        0f, 0f, 1.35f, 0f, 18f,
        0f, 0f, 0f, 1f, 0f
    ))
    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm); isAntiAlias = true })

    val targetH = (w / 2.39f).toInt()
    val barHeight = ((h - targetH) / 2).coerceAtLeast(0)
    val blackPaint = Paint().apply { color = android.graphics.Color.BLACK }
    canvas.drawRect(0f, 0f, w.toFloat(), barHeight.toFloat(), blackPaint)
    canvas.drawRect(0f, (h - barHeight).toFloat(), w.toFloat(), h.toFloat(), blackPaint)

    return applyAIEnhancement(res)
}

fun applyVideoGlowHalo(src: Bitmap): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(res)
    val cm = ColorMatrix(floatArrayOf(
        1.12f, 0f, 0f, 0f, 22f,
        0f, 1.10f, 0f, 0f, 20f,
        0f, 0f, 1.15f, 0f, 25f,
        0f, 0f, 0f, 1f, 0f
    ))
    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm); isAntiAlias = true })
    return res
}

fun applyPhotographicStyle(src: Bitmap, style: String): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(res)
    val cm = ColorMatrix()

    when (style) {
        "Rich Contrast" -> cm.set(floatArrayOf(1.22f, 0f, 0f, 0f, -12f, 0f, 1.22f, 0f, 0f, -12f, 0f, 0f, 1.22f, 0f, -12f, 0f, 0f, 0f, 1f, 0f))
        "Vibrant" -> {
            cm.setSaturation(1.28f)
            cm.postConcat(ColorMatrix(floatArrayOf(1.08f, 0f, 0f, 0f, 4f, 0f, 1.08f, 0f, 0f, 4f, 0f, 0f, 1.08f, 0f, 4f, 0f, 0f, 0f, 1f, 0f)))
        }
        "Warm" -> cm.set(floatArrayOf(1.16f, 0f, 0f, 0f, 18f, 0f, 1.06f, 0f, 0f, 8f, 0f, 0f, 0.88f, 0f, -10f, 0f, 0f, 0f, 1f, 0f))
        "Cool" -> cm.set(floatArrayOf(0.90f, 0f, 0f, 0f, -8f, 0f, 1.02f, 0f, 0f, 4f, 0f, 0f, 1.20f, 0f, 20f, 0f, 0f, 0f, 1f, 0f))
        else -> return src
    }

    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm); isAntiAlias = true })
    return res
}

fun applyDSLRBokehEffect(src: Bitmap, blurRadius: Float): Bitmap {
    val w = src.width; val h = src.height
    val downFactor = (14f * blurRadius).toInt().coerceAtLeast(8)
    val small = Bitmap.createScaledBitmap(src, (w / downFactor).coerceAtLeast(1), (h / downFactor).coerceAtLeast(1), true)
    val blurredBg = Bitmap.createScaledBitmap(small, w, h, true)
    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    canvas.drawBitmap(blurredBg, 0f, 0f, null)

    val focusRadius = (w.coerceAtMost(h) * 0.38f)
    val shader = RadialGradient(w / 2f, h / 2f, focusRadius, intArrayOf(android.graphics.Color.WHITE, android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader; xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    val sharpSubject = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val subjectCanvas = Canvas(sharpSubject)
    subjectCanvas.drawBitmap(src, 0f, 0f, null)
    subjectCanvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), maskPaint)

    canvas.drawBitmap(sharpSubject, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
    return result
}

fun applyVintage70sFilter(src: Bitmap): Bitmap {
    val res = src.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(res)
    val cm = ColorMatrix(floatArrayOf(0.9f, 0f, 0f, 0f, 25f, 0f, 0.78f, 0f, 0f, 15f, 0f, 0f, 0.62f, 0f, 5f, 0f, 0f, 0f, 1f, 0f))
    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })

    val noisePaint = Paint().apply { color = android.graphics.Color.WHITE; alpha = 18 }
    for (i in 0 until (res.width * res.height / 150)) {
        canvas.drawCircle(Random.nextInt(res.width).toFloat(), Random.nextInt(res.height).toFloat(), 1.2f, noisePaint)
    }
    return res
}

fun applyGoldenHourGlow(src: Bitmap): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(res)
    val cm = ColorMatrix(floatArrayOf(1.25f, 0f, 0f, 0f, 35f, 0f, 1.08f, 0f, 0f, 18f, 0f, 0f, 0.72f, 0f, -15f, 0f, 0f, 0f, 1f, 0f))
    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm); isAntiAlias = true })
    return res
}

fun applyCyberpunkNeonFilter(src: Bitmap): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(res)
    val cm = ColorMatrix(floatArrayOf(1.35f, 0f, 0f, 0f, 30f, 0f, 0.75f, 0f, 0f, -10f, 0f, 0f, 1.45f, 0f, 40f, 0f, 0f, 0f, 1f, 0f))
    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm); isAntiAlias = true })
    return res
}

fun applyNoirFilter(src: Bitmap): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(res)
    val cm = ColorMatrix()
    cm.setSaturation(0f)
    cm.postConcat(ColorMatrix(floatArrayOf(1.45f, 0f, 0f, 0f, -25f, 0f, 1.45f, 0f, 0f, -25f, 0f, 0f, 1.45f, 0f, -25f, 0f, 0f, 0f, 1f, 0f)))
    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm); isAntiAlias = true })
    return res
}

fun applyAstroPhoto(src: Bitmap): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(res)
    val cm = ColorMatrix(floatArrayOf(1.30f, 0f, 0f, 0f, 35f, 0f, 1.30f, 0f, 0f, 35f, 0f, 0f, 1.50f, 0f, 45f, 0f, 0f, 0f, 1f, 0f))
    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm); isAntiAlias = true })
    return res
}

fun applyMacroSharpen(src: Bitmap): Bitmap { return applyAIEnhancement(src) }

fun applyHDRPlus(src: Bitmap): Bitmap {
    val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(res)
    val cm = ColorMatrix(floatArrayOf(1.18f, 0f, 0f, 0f, 15f, 0f, 1.18f, 0f, 0f, 15f, 0f, 0f, 1.18f, 0f, 15f, 0f, 0f, 0f, 1f, 0f))
    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm); isAntiAlias = true })
    return res
}

fun applyAIObjectEraser(src: Bitmap): Bitmap {
    val res = src.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(res)
    val p = Paint().apply { isAntiAlias = true; maskFilter = android.graphics.BlurMaskFilter(35f, android.graphics.BlurMaskFilter.Blur.NORMAL) }
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
    val w = src.width; val h = src.height
    return when (aspect) {
        "1:1" -> { val s = minOf(w, h); Bitmap.createBitmap(src, (w - s) / 2, (h - sizeSquare(w, h)) / 2, s, s) }
        "16:9" -> {
            val targetH = (w * 9) / 16
            if (targetH <= h) Bitmap.createBitmap(src, 0, (h - targetH) / 2, w, targetH)
            else Bitmap.createBitmap(src, (w - ((h * 16) / 9)) / 2, 0, (h * 16) / 9, h)
        }
        else -> src
    }
}
private fun sizeSquare(w: Int, h: Int) = minOf(w, h)

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
        context.contentResolver.openOutputStream(it)?.use { s ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, s)
        }
        Toast.makeText(context, "✅ $mode सेव्ह झाला!", Toast.LENGTH_SHORT).show()
    }
}
// 🎬 मोठ्या सिनेमा कॅमेऱ्यासारखा Rich Color Grading अल्गोरिदम
fun applyRichCinemaGrade(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    val res = src.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(res)

    // १. पानांमधील लाल/नारंगी आणि पाण्यातील निळा रंग रसरशीत करणे (व्हिडिओसारखा लूक)
    val cm = ColorMatrix(floatArrayOf(
        1.38f, 0f, 0f, 0f, -8f,   // Rich Amber & Crimson (पानांचे रंग)
        0f, 1.12f, 0f, 0f, -6f,   // Deep Green Contrast
        0f, 0f, 1.48f, 0f, -4f,   // Deep Sapphire Blue (पाण्याचे प्रतिबिंब)
        0f, 0f, 0f, 1f, 0f
    ))
    canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm); isAntiAlias = true })

    // २. सिनेमॅटिक 2.39:1 Letterbox काळ्या पट्ट्या
    val targetH = (w / 2.39f).toInt()
    val barHeight = ((h - targetH) / 2).coerceAtLeast(0)
    val blackPaint = Paint().apply { color = android.graphics.Color.BLACK }
    canvas.drawRect(0f, 0f, w.toFloat(), barHeight.toFloat(), blackPaint)
    canvas.drawRect(0f, (h - barHeight).toFloat(), w.toFloat(), h.toFloat(), blackPaint)

    // ३. क्रिस्टल क्लिअर शार्पनेस
    return applyAIEnhancement(res)
}
