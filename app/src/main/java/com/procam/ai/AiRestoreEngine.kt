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
import java.io.IOException
import java.util.concurrent.TimeUnit

object AiRestoreEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // ⚡ पुसट/१०x फोटोला Remini/ChatGPT सारखे 4K Face Restore करणे
    fun restorePhotoWithGenerativeAI(
        context: Context,
        inputBitmap: Bitmap,
        apiToken: String,
        onResult: (Bitmap?) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())

        if (apiToken.isBlank() || !apiToken.startsWith("r8_")) {
            mainHandler.post {
                Toast.makeText(context, "कृपया योग्य Replicate API Token टाका!", Toast.LENGTH_LONG).show()
                onResult(null)
            }
            return
        }

        Thread {
            try {
                // १. फोटोला सुरक्षित बेस६४ (Data URI) मध्ये रूपांतरित करणे
                val stream = ByteArrayOutputStream()
                // रिझोल्यूशन मॅक्सिमम १०२४px ठेवून सुपरफास्ट अपलोड
                val scale = 1024f / maxOf(inputBitmap.width, inputBitmap.height)
                val resized = if (scale < 1f) {
                    Bitmap.createScaledBitmap(inputBitmap, (inputBitmap.width * scale).toInt(), (inputBitmap.height * scale).toInt(), true)
                } else inputBitmap

                resized.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                val base64Image = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                val dataUri = "data:image/jpeg;base64,$base64Image"

                // २. जगातील सर्वोत्तम Face Restoration AI मॉडेल (CodeFormer) ची विनंती तयार करणे
                val jsonBody = JSONObject().apply {
                    put("version", "7de2ea26c616d5bfde245ad0d11cf0c5c3f42b2ce66bef47e16a77779a73e19a")
                    put("input", JSONObject().apply {
                        put("image", dataUri)
                        put("codeformer_fidelity", 0.7) // चेहरा मूळ व्यक्तीसारखाच ठेवणे
                        put("upscale", 2)               // २ पट सुपर रिझोल्यूशन
                        put("face_upsample", true)      // डोळे, पापण्या, केस आणि त्वचा हुबेहूब तयार करणे
                    })
                }

                val request = Request.Builder()
                    .url("https://api.replicate.com/v1/predictions")
                    .addHeader("Authorization", "Bearer $apiToken")
                    .addHeader("Content-Type", "application/json")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                mainHandler.post {
                    Toast.makeText(context, "⚡ AI जादू सुरू झाली... ३ ते ५ सेकंद थांबा", Toast.LENGTH_SHORT).show()
                }

                val response = client.newCall(request).execute()
                val responseStr = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    mainHandler.post {
                        Toast.makeText(context, "AI सर्व्हर त्रुटी: ${response.code}", Toast.LENGTH_SHORT).show()
                        onResult(null)
                    }
                    return@Thread
                }

                val predictionJson = JSONObject(responseStr)
                var status = predictionJson.optString("status")
                val pollUrl = predictionJson.getJSONObject("urls").getString("get")

                // ३. AI फोटो तयार करेपर्यंत वाट पाहणे (Polling 1-2 सेकंदात)
                var outputImageUrl: String? = null
                var attempts = 0
                while (status != "succeeded" && status != "failed" && attempts < 25) {
                    Thread.sleep(1500)
                    attempts++

                    val pollRequest = Request.Builder()
                        .url(pollUrl)
                        .addHeader("Authorization", "Bearer $apiToken")
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

                // ४. AI ने तयार केलेला चकचकीत HD फोटो डाऊनलोड करणे
                if (outputImageUrl != null && outputImageUrl.isNotBlank()) {
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
