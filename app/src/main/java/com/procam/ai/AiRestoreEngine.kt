package com.procam.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object AiRestoreEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun restorePhotoWithGenerativeAI(
        context: Context,
        inputBitmap: Bitmap,
        apiToken: String,
        onResult: (Bitmap?) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())

        val token = apiToken.trim()
        if (token.isBlank() || !token.startsWith("r8_")) {
            mainHandler.post {
                Toast.makeText(context, "कृपया योग्य Replicate API Token (r8_...) टाका!", Toast.LENGTH_LONG).show()
                onResult(null)
            }
            return
        }

        Thread {
            try {
                // १. फोटोचे रिझोल्यूशन सेफ ठेवून Base64 Data URI बनवणे
                val stream = ByteArrayOutputStream()
                val maxDim = maxOf(inputBitmap.width, inputBitmap.height)
                val scale = if (maxDim > 1024) 1024f / maxDim else 1f
                val resized = if (scale < 1f) {
                    Bitmap.createScaledBitmap(inputBitmap, (inputBitmap.width * scale).toInt(), (inputBitmap.height * scale).toInt(), true)
                } else inputBitmap

                resized.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                val base64Image = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                val dataUri = "data:image/jpeg;base64,$base64Image"

                // २. अधिकृत CodeFormer + Real-ESRGAN (चेहरा + संपूर्ण बॅकग्राऊंड दोन्हीसाठी)
                val jsonBody = JSONObject().apply {
                    put("input", JSONObject().apply {
                        put("image", dataUri)
                        put("upscale", 2)
                        put("face_upsample", true)
                        put("background_enhance", true)
                        put("codeformer_fidelity", 0.7)
                    })
                }

                val request = Request.Builder()
                    .url("https://api.replicate.com/v1/models/sczhou/codeformer/predictions")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "wait")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                mainHandler.post {
                    Toast.makeText(context, "⚡ AI जादू सुरू झाली... काही सेकंद थांबा", Toast.LENGTH_SHORT).show()
                }

                val response = client.newCall(request).execute()
                val responseStr = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val errorDetail = try {
                        val errObj = JSONObject(responseStr)
                        errObj.optString("detail", errObj.optString("error", "त्रुटी: ${response.code}"))
                    } catch (_: Exception) {
                        "त्रुटी: ${response.code}"
                    }
                    mainHandler.post {
                        Toast.makeText(context, "AI सर्व्हर त्रुटी: $errorDetail", Toast.LENGTH_LONG).show()
                        onResult(null)
                    }
                    return@Thread
                }

                val predictionJson = JSONObject(responseStr)
                var status = predictionJson.optString("status")
                var outputImageUrl: String? = null

                if (status == "succeeded") {
                    outputImageUrl = predictionJson.optString("output")
                } else {
                    val urlsObj = predictionJson.optJSONObject("urls")
                    val pollUrl = urlsObj?.optString("get")

                    if (pollUrl != null) {
                        var attempts = 0
                        while (status != "succeeded" && status != "failed" && attempts < 30) {
                            Thread.sleep(1500)
                            attempts++

                            val pollRequest = Request.Builder()
                                .url(pollUrl)
                                .addHeader("Authorization", "Bearer $token")
                                .get()
                                .build()

                            val pollResponse = client.newCall(pollRequest).execute()
                            val pollJson = JSONObject(pollResponse.body?.string() ?: "{}")
                            status = pollJson.optString("status")

                            if (status == "succeeded") {
                                outputImageUrl = pollJson.optString("output")
                                break
                            }
                        }
                    }
                }

                // ३. तयार झालेला 4K फोटो थेट डाऊनलोड करून गॅलरीत देणे
                if (!outputImageUrl.isNullOrBlank()) {
                    val downloadReq = Request.Builder().url(outputImageUrl).get().build()
                    val downloadResp = client.newCall(downloadReq).execute()
                    val imageBytes = downloadResp.body?.bytes()

                    if (imageBytes != null) {
                        val restoredBmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        mainHandler.post {
                            Toast.makeText(context, "🎉 AI 4K Restore यशस्वी!", Toast.LENGTH_SHORT).show()
                            onResult(restoredBmp)
                        }
                        return@Thread
                    }
                }

                mainHandler.post {
                    Toast.makeText(context, "AI प्रक्रिया पूर्ण होऊ शकली नाही", Toast.LENGTH_SHORT).show()
                    onResult(null)
                }

            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(context, "त्रुटी: ${e.message}", Toast.LENGTH_SHORT).show()
                    onResult(null)
                }
            }
        }.start()
    }
}
