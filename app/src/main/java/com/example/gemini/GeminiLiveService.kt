package com.example.gemini

import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.audio.AudioTrackPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

interface GeminiLiveCallback {
    fun onConnectionStateChanged(isConnected: Boolean, statusText: String)
    fun onUserTranscript(text: String)
    fun onModelTranscript(text: String, isFinished: Boolean)
    fun onToolCall(callId: String, functionName: String, args: Map<String, Any?>)
    fun onError(errorMessage: String)
}

class GeminiLiveService(
    private val audioPlayer: AudioTrackPlayer,
    private val callback: GeminiLiveCallback
) {
    private val tag = "GeminiLiveService"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private val conversationHistory = JSONArray()

    private val liveModel = "models/gemini-2.5-flash-native-audio-preview-12-2025"
    private val restModel = "gemini-2.5-flash-native-audio-preview-12-2025"

    fun getApiKey(): String {
        val key = BuildConfig.GEMINI_API_KEY
        return if (key == "MY_GEMINI_API_KEY" || key.isBlank()) "" else key
    }

    /**
     * Connect to Gemini Live WebSocket
     */
    fun connectLive(scope: CoroutineScope) {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            callback.onConnectionStateChanged(false, "API Key Not Configured")
            callback.onError("Please set your GEMINI_API_KEY in the Secrets panel or .env file.")
            return
        }

        if (isConnected && webSocket != null) {
            return
        }

        callback.onConnectionStateChanged(false, "Connecting to Gemini Live...")

        val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(wsUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(tag, "Gemini Live WebSocket opened")
                isConnected = true
                sendSetupMessage(webSocket)
                scope.launch(Dispatchers.Main) {
                    callback.onConnectionStateChanged(true, "Live Connected (Arushi Ready)")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch(Dispatchers.IO) {
                    handleWebSocketMessage(text)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                scope.launch(Dispatchers.Main) {
                    callback.onConnectionStateChanged(false, "Disconnected: $reason")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "Gemini Live WebSocket failure: ${t.message}", t)
                isConnected = false
                scope.launch(Dispatchers.Main) {
                    callback.onConnectionStateChanged(false, "Live Standby (REST Fallback Active)")
                }
            }
        })
    }

    private fun sendSetupMessage(ws: WebSocket) {
        try {
            val setupObj = JSONObject().apply {
                put("model", liveModel)
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().apply { put("AUDIO") })
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", "Aoede")
                            })
                        })
                    })
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", GeminiToolDeclarations.SYSTEM_PROMPT)
                        })
                    })
                })
                put("tools", GeminiToolDeclarations.getToolsJsonArray())
            }

            val root = JSONObject().apply {
                put("setup", setupObj)
            }
            ws.send(root.toString())
            Log.d(tag, "Setup message sent to Gemini Live")
        } catch (e: Exception) {
            Log.e(tag, "Error building setup message", e)
        }
    }

    /**
     * Stream microphone audio chunk to Gemini Live
     */
    fun sendAudioChunk(pcmChunk: ByteArray) {
        if (!isConnected || webSocket == null) return

        try {
            val base64Data = Base64.encodeToString(pcmChunk, Base64.NO_WRAP)
            val msg = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("mediaChunks", JSONArray().apply {
                        put(JSONObject().apply {
                            put("mimeType", "audio/pcm;rate=16000")
                            put("data", base64Data)
                        })
                    })
                })
            }
            webSocket?.send(msg.toString())
        } catch (e: Exception) {
            Log.e(tag, "Error sending audio chunk", e)
        }
    }

    /**
     * Send tool response back to Gemini Live
     */
    fun sendToolResponse(callId: String, resultString: String) {
        if (!isConnected || webSocket == null) return

        try {
            val msg = JSONObject().apply {
                put("toolResponse", JSONObject().apply {
                    put("functionResponses", JSONArray().apply {
                        put(JSONObject().apply {
                            put("response", JSONObject().apply {
                                put("output", JSONObject().apply {
                                    put("result", resultString)
                                })
                            })
                            put("id", callId)
                        })
                    })
                })
            }
            webSocket?.send(msg.toString())
            Log.d(tag, "Sent tool response for $callId: $resultString")
        } catch (e: Exception) {
            Log.e(tag, "Error sending tool response", e)
        }
    }

    /**
     * Send text input through Gemini Live WebSocket
     */
    fun sendTextLive(text: String): Boolean {
        if (!isConnected || webSocket == null) return false

        try {
            val msg = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", text) })
                            })
                        })
                    })
                    put("turnComplete", true)
                })
            }
            webSocket?.send(msg.toString())
            return true
        } catch (e: Exception) {
            Log.e(tag, "Error sending text over live websocket", e)
            return false
        }
    }

    private fun handleWebSocketMessage(jsonString: String) {
        try {
            val root = JSONObject(jsonString)

            // Server Content (Audio / Text)
            if (root.has("serverContent")) {
                val serverContent = root.getJSONObject("serverContent")

                if (serverContent.optBoolean("interrupted", false)) {
                    audioPlayer.interrupt()
                }

                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            // Text transcript
                            if (part.has("text")) {
                                val text = part.getString("text")
                                callback.onModelTranscript(text, false)
                            }
                            // Audio PCM
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val mimeType = inlineData.optString("mimeType", "")
                                val base64Audio = inlineData.optString("data", "")
                                if (base64Audio.isNotBlank()) {
                                    val audioBytes = Base64.decode(base64Audio, Base64.DEFAULT)
                                    val sampleRate = if (mimeType.contains("rate=16000")) 16000 else 24000
                                    audioPlayer.enqueueChunk(audioBytes, sampleRate)
                                }
                            }
                        }
                    }
                }

                if (serverContent.optBoolean("turnComplete", false)) {
                    callback.onModelTranscript("", true)
                }
            }

            // Tool Call
            if (root.has("toolCall")) {
                val toolCall = root.getJSONObject("toolCall")
                val functionCalls = toolCall.optJSONArray("functionCalls")
                if (functionCalls != null) {
                    for (i in 0 until functionCalls.length()) {
                        val fn = functionCalls.getJSONObject(i)
                        val name = fn.getString("name")
                        val id = fn.optString("id", java.util.UUID.randomUUID().toString())
                        val argsObj = fn.optJSONObject("args")
                        val argsMap = mutableMapOf<String, Any?>()
                        if (argsObj != null) {
                            val keys = argsObj.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                argsMap[key] = argsObj.get(key)
                            }
                        }
                        callback.onToolCall(id, name, argsMap)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error parsing WebSocket message", e)
        }
    }

    /**
     * Robust REST Multimodal Voice Engine with native Audio output & Function Calling
     */
    suspend fun executeRestInteraction(
        userPrompt: String,
        onActionToExecute: suspend (String, Map<String, Any?>) -> String
    ): RestResult = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            return@withContext RestResult.Error("API key not configured.")
        }

        try {
            // Append user turn to history
            val userTurn = JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", userPrompt) })
                })
            }
            conversationHistory.put(userTurn)

            // 1. First REST call: send prompt + tools
            val requestBodyObj = JSONObject().apply {
                put("contents", conversationHistory)
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", GeminiToolDeclarations.SYSTEM_PROMPT) })
                    })
                })
                put("tools", GeminiToolDeclarations.getToolsJsonArray())
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().apply {
                        put("AUDIO")
                        put("TEXT")
                    })
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", "Aoede")
                            })
                        })
                    })
                })
            }

            val url = "https://generativelanguage.googleapis.com/v1beta/models/$restModel:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .post(requestBodyObj.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val respString = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext RestResult.Error("Gemini API error (${response.code}): $respString")
            }

            val respJson = JSONObject(respString)
            val candidate = respJson.optJSONArray("candidates")?.optJSONObject(0)
            val content = candidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")

            var modelSpokenText = ""
            var audioBytesToPlay: ByteArray? = null
            var toolCallName: String? = null
            var toolCallArgs = mapOf<String, Any?>()

            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val p = parts.getJSONObject(i)
                    if (p.has("text")) {
                        modelSpokenText += p.getString("text") + " "
                    }
                    if (p.has("inlineData")) {
                        val inline = p.getJSONObject("inlineData")
                        val b64 = inline.optString("data")
                        if (b64.isNotBlank()) {
                            audioBytesToPlay = Base64.decode(b64, Base64.DEFAULT)
                        }
                    }
                    if (p.has("functionCall")) {
                        val fn = p.getJSONObject("functionCall")
                        toolCallName = fn.getString("name")
                        val argsObj = fn.optJSONObject("args")
                        val map = mutableMapOf<String, Any?>()
                        if (argsObj != null) {
                            val keys = argsObj.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                map[k] = argsObj.get(k)
                            }
                        }
                        toolCallArgs = map
                    }
                }
            }

            // If Gemini issued a function call, execute it and send the response back!
            if (!toolCallName.isNullOrBlank()) {
                val actionResultString = onActionToExecute(toolCallName, toolCallArgs)

                // Append model's functionCall turn
                val modelFunctionTurn = JSONObject().apply {
                    put("role", "model")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("functionCall", JSONObject().apply {
                                put("name", toolCallName)
                                put("args", JSONObject(toolCallArgs))
                            })
                        })
                    })
                }
                conversationHistory.put(modelFunctionTurn)

                // Append function response turn
                val functionResponseTurn = JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("functionResponse", JSONObject().apply {
                                put("name", toolCallName)
                                put("response", JSONObject().apply {
                                    put("content", actionResultString)
                                })
                            })
                        })
                    })
                }
                conversationHistory.put(functionResponseTurn)

                // Call model again to generate spoken voice confirmation
                val followUpRequest = JSONObject().apply {
                    put("contents", conversationHistory)
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", GeminiToolDeclarations.SYSTEM_PROMPT) })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().apply {
                            put("AUDIO")
                            put("TEXT")
                        })
                        put("speechConfig", JSONObject().apply {
                            put("voiceConfig", JSONObject().apply {
                                put("prebuiltVoiceConfig", JSONObject().apply {
                                    put("voiceName", "Aoede")
                                })
                            })
                        })
                    })
                }

                val followUpCall = client.newCall(
                    Request.Builder()
                        .url(url)
                        .post(followUpRequest.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute()

                val followUpString = followUpCall.body?.string() ?: ""
                val followUpJson = JSONObject(followUpString)
                val followCandidate = followUpJson.optJSONArray("candidates")?.optJSONObject(0)
                val followParts = followCandidate?.optJSONObject("content")?.optJSONArray("parts")

                var finalSpeechText = ""
                var finalAudio: ByteArray? = null
                if (followParts != null) {
                    for (i in 0 until followParts.length()) {
                        val p = followParts.getJSONObject(i)
                        if (p.has("text")) {
                            finalSpeechText += p.getString("text") + " "
                        }
                        if (p.has("inlineData")) {
                            val b64 = p.getJSONObject("inlineData").optString("data")
                            if (b64.isNotBlank()) {
                                finalAudio = Base64.decode(b64, Base64.DEFAULT)
                            }
                        }
                    }
                }

                if (finalAudio != null) {
                    audioPlayer.enqueueChunk(finalAudio, 24000)
                }

                return@withContext RestResult.Success(
                    spokenText = finalSpeechText.trim(),
                    toolExecuted = toolCallName
                )
            } else {
                // Play audio if available
                if (audioBytesToPlay != null) {
                    audioPlayer.enqueueChunk(audioBytesToPlay, 24000)
                }

                // Add to history
                val modelTurn = JSONObject().apply {
                    put("role", "model")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", modelSpokenText.trim()) })
                    })
                }
                conversationHistory.put(modelTurn)

                return@withContext RestResult.Success(
                    spokenText = modelSpokenText.trim(),
                    toolExecuted = null
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "REST interaction failed", e)
            return@withContext RestResult.Error("Failed to communicate with Arushi: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            webSocket?.close(1000, "App closed")
        } catch (_: Exception) {}
        webSocket = null
        isConnected = false
    }

    fun isLiveConnected(): Boolean = isConnected
}

sealed class RestResult {
    data class Success(val spokenText: String, val toolExecuted: String?) : RestResult()
    data class Error(val errorMessage: String) : RestResult()
}
