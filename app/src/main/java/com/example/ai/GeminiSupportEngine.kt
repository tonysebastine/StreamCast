package com.example.ai

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GeminiSupportEngine {
    private val TAG = "GeminiSupportEngine"
    private val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-pro-preview:generateContent"

    private val client = OkHttpClient.Builder()
        .connectTimeout(90, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun getTroubleshootingGuide(userQuery: String, networkLogs: String): String = withContext(Dispatchers.IO) {
        val apiKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            ""
        }

        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext "AI Assistance is currently in offline help mode because the AI Studio Gemini API key is not configured. Please add your GEMINI_API_KEY in the Secrets panel.\n\n" +
                    "Offline Answer:\nTo fix connection failures, check if the phone and TV are on the EXACT same Wi-Fi SSID network. AP isolation on dual-band routers is the most common issue. Ensure multicast is enabled on your router settings."
        }

        val systemInstruction = "You are an Elite Network Protocol Engineer and Casting Specialist. " +
                "You assist users in troubleshooting casting errors (AP router isolation, dynamic IP changes, " +
                "SSDP/mDNS packet drops, video codec unrenderability e.g., casting MKV/H.265 to audio-only or older Chromecast). " +
                "Use high intelligence and reasoning to diagnose the issue based on the network state and user query. " +
                "Keep solutions highly actionable, clear, structured, and free of excessive academic jargon."

        try {
            // Build the JSON payload manually for extreme reliability using standard org.json API
            val root = JSONObject()

            // contents
            val contentsArray = JSONArray()
            val contentObj = JSONObject()
            contentObj.put("role", "user")
            val partsArray = JSONArray()
            val partObj = JSONObject()
            
            val fullPrompt = "Network/Device Diagnostics Log:\n$networkLogs\n\nUser Question: $userQuery"
            partObj.put("text", fullPrompt)
            partsArray.put(partObj)
            contentObj.put("parts", partsArray)
            contentsArray.put(contentObj)
            root.put("contents", contentsArray)

            // systemInstruction
            val systemInstructionObj = JSONObject()
            val systemPartsArray = JSONArray()
            val systemPartObj = JSONObject()
            systemPartObj.put("text", systemInstruction)
            systemPartsArray.put(systemPartObj)
            systemInstructionObj.put("parts", systemPartsArray)
            root.put("systemInstruction", systemInstructionObj)

            // generationConfig with HIGH thinking level config
            val generationConfig = JSONObject()
            val thinkingConfig = JSONObject()
            thinkingConfig.put("thinkingLevel", "HIGH") // Activate HIGH thinking mode
            generationConfig.put("thinkingConfig", thinkingConfig)
            // No maxOutputTokens as requested by the guidelines
            root.put("generationConfig", generationConfig)

            val jsonBody = root.toString()
            Log.d(TAG, "Request payload: $jsonBody")

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonBody.toRequestBody(mediaType)

            val request = Request.Builder()
                .url("$BASE_URL?key=$apiKey")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    Log.e(TAG, "Gemini API failed: Code ${response.code}, Body: $errBody")
                    return@withContext "Network Error (HTTP ${response.code}): Failed to get AI suggestions. Please check your internet connection or verify your Gemini API key."
                }

                val resBody = response.body?.string() ?: return@withContext "Empty response received from the Gemini Engine."
                Log.d(TAG, "Response size: ${resBody.length}")
                
                val responseJson = JSONObject(resBody)
                val candidates = responseJson.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstCandidate = candidates.getJSONObject(0)
                    val content = firstCandidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        // The text could include reasoning sections or normal output
                        return@withContext parts.getJSONObject(0).optString("text", "No text output found.")
                    }
                }
                return@withContext "AI did not produce valid instructions. Check casting target format correctness."
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network timeout / IO exception in Gemini", e)
            return@withContext "Network Error: Timeout connecting to Gemini AI reasoning servers. Check local network router settings."
        } catch (e: Exception) {
            Log.e(TAG, "Gemini parsing crashed", e)
            return@withContext "Error analyzing logs: ${e.localizedMessage}"
        }
    }
}
