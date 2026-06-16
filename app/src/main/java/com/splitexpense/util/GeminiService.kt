package com.splitexpense.util

import android.util.Base64
import com.splitexpense.data.db.entity.Category
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ParsedBillItem(val name: String, val amount: Double)

data class ParsedBill(
    val description: String,
    val total: Double,
    val category: Category,
    val items: List<ParsedBillItem>
)

object GeminiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val MODELS = listOf(
        // Flagship Speed & Intelligence (Fits 250 req/day)
        "gemini-3.5-flash",
        "gemini-2.5-flash",

        // Heavy Reasoning / Complex Coding (Fits 100 req/day)
        // Safe for you since 50 < 100, but don't spam it in tight loops!
        "gemini-2.5-pro",

        // Ultra-High Safety Net (Fits 1,000 req/day)
        "gemini-3.1-flash-lite",
        "gemini-2.5-flash-lite"
    )

    private val PROMPT = """
        Analyze this receipt/bill image and extract the data.
        Return ONLY valid JSON — no markdown, no explanation, just the JSON object.

        Required format:
        {
          "description": "merchant name or brief bill description (max 50 chars)",
          "total": 0.00,
          "category": "FOOD",
          "items": [
            {"name": "item name (max 40 chars)", "amount": 0.00}
          ]
        }

        Rules:
        - category must be exactly one of: FOOD, TRAVEL, UTILITIES, ENTERTAINMENT, SHOPPING, HEALTH, OTHER
        - total is the grand total including taxes
        - items are the individual line items; fold small charges (service charge, tax) into total if not clearly itemized
        - amounts are plain numbers (no currency symbols)
        - if the image is unclear, make a best guess
    """.trimIndent()

    suspend fun parseReceipt(imageBytes: ByteArray, apiKey: String): Result<ParsedBill> {
        if (apiKey.isBlank()) {
            return Result.failure(Exception("No Gemini API key set."))
        }

        val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        val requestJson = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", PROMPT))
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                    })
                }
            ))
            put("generationConfig", JSONObject().apply {
                put("response_mime_type", "application/json")
                put("temperature", 0.1)
            })
        }

        var lastError: Throwable = Exception("No models available to try")

        for (model in MODELS) {
            val runResult = runCatching {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val request = Request.Builder()
                    .url(url)
                    .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val responseText = client.newCall(request).awaitString()

                // Extract the JSON text from Gemini's response envelope
                val candidates = JSONObject(responseText).getJSONArray("candidates")
                val contentText = candidates
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

                parseBillJson(JSONObject(contentText))
            }

            if (runResult.isSuccess) {
                return runResult
            } else {
                lastError = runResult.exceptionOrNull() ?: lastError
                android.util.Log.w("GeminiService", "Model $model failed, trying fallback...", lastError)
            }
        }

        return Result.failure(lastError)
    }

    /**
     * Suspends until the OkHttp call completes and returns the response body as a String.
     * Cancels the in-flight HTTP request if the calling coroutine is cancelled, preventing
     * thread-pool exhaustion when the user navigates away during a long request.
     */
    private suspend fun Call.awaitString(): String = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    if (!r.isSuccessful) {
                        val body = r.body?.string() ?: ""
                        val msg = if (r.code == 429)
                            "Gemini API Quota Exceeded (429). Please check your plan limits or try again later."
                        else
                            "Gemini API error ${r.code}: ${body.take(200)}"
                        cont.resumeWithException(IOException(msg))
                    } else {
                        val body = r.body?.string()
                        if (body != null) cont.resume(body)
                        else cont.resumeWithException(IOException("Empty response body"))
                    }
                }
            }
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCompleted) cont.resumeWithException(e)
            }
        })
    }

    private fun parseBillJson(json: JSONObject): ParsedBill {
        val description = json.optString("description", "Scanned Bill")
        val total       = json.optDouble("total", 0.0)
        val categoryStr = json.optString("category", "OTHER")
        val category    = runCatching { Category.valueOf(categoryStr) }.getOrDefault(Category.OTHER)

        val itemsArray = json.optJSONArray("items") ?: JSONArray()
        val items = (0 until itemsArray.length()).mapNotNull { i ->
            val obj    = itemsArray.optJSONObject(i) ?: return@mapNotNull null
            val name   = obj.optString("name", "Item ${i + 1}")
            val amount = obj.optDouble("amount", 0.0)
            if (amount > 0) ParsedBillItem(name, amount) else null
        }

        // If Gemini returned no items, create a single item for the total
        val finalItems = items.ifEmpty { listOf(ParsedBillItem(description, total)) }

        return ParsedBill(
            description = description,
            total       = if (total > 0) total else finalItems.sumOf { it.amount },
            category    = category,
            items       = finalItems
        )
    }
}
