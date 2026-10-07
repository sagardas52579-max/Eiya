package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioRecordManager
import com.example.audio.AudioTrackPlayer
import com.example.bridge.AndroidActionBridge
import com.example.bridge.BridgeResult
import com.example.gemini.GeminiLiveCallback
import com.example.gemini.GeminiLiveService
import com.example.gemini.RestResult
import com.example.model.ActionExecution
import com.example.model.ActionStatus
import com.example.model.ChatMessage
import com.example.model.ContactItem
import com.example.model.MessageSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ArushiUiState(
    val connectionStatus: String = "Initializing...",
    val isLiveConnected: Boolean = false,
    val isRecording: Boolean = false,
    val isSpeaking: Boolean = false,
    val amplitude: Float = 0f,
    val messages: List<ChatMessage> = emptyList(),
    val currentStreamingText: String = "",
    val activeAction: ActionExecution? = null,
    val detectedLanguage: String = "Auto-Detect: Hindi / English / Hinglish",
    val contacts: List<ContactItem> = emptyList(),
    val showContactsSheet: Boolean = false,
    val apiKeyConfigured: Boolean = true,
    val errorMessage: String? = null
)

class ArushiViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ArushiUiState())
    val uiState: StateFlow<ArushiUiState> = _uiState.asStateFlow()

    val actionBridge = AndroidActionBridge(application.applicationContext)
    private val audioPlayer = AudioTrackPlayer()
    private val audioRecorder = AudioRecordManager(application.applicationContext)

    private val geminiLiveCallback = object : GeminiLiveCallback {
        override fun onConnectionStateChanged(isConnected: Boolean, statusText: String) {
            _uiState.update {
                it.copy(
                    isLiveConnected = isConnected,
                    connectionStatus = statusText
                )
            }
        }

        override fun onUserTranscript(text: String) {
            if (text.isBlank()) return
            val lang = detectLanguage(text)
            _uiState.update {
                it.copy(
                    messages = it.messages + ChatMessage(
                        sender = MessageSender.USER,
                        text = text,
                        detectedLanguage = lang
                    ),
                    detectedLanguage = "Detected: $lang"
                )
            }
        }

        override fun onModelTranscript(text: String, isFinished: Boolean) {
            _uiState.update {
                val updatedStream = if (isFinished) "" else it.currentStreamingText + text
                val messages = if (isFinished && it.currentStreamingText.isNotBlank()) {
                    it.messages + ChatMessage(
                        sender = MessageSender.ARUSHI,
                        text = it.currentStreamingText.trim(),
                        detectedLanguage = detectLanguage(it.currentStreamingText)
                    )
                } else {
                    it.messages
                }
                it.copy(
                    currentStreamingText = updatedStream,
                    messages = messages
                )
            }
        }

        override fun onToolCall(callId: String, functionName: String, args: Map<String, Any?>) {
            viewModelScope.launch(Dispatchers.Main) {
                val actionResult = executeBridgeAction(functionName, args)
                if (::geminiLive.isInitialized) {
                    geminiLive.sendToolResponse(callId, actionResult.details)
                }
            }
        }

        override fun onError(errorMessage: String) {
            _uiState.update { it.copy(errorMessage = errorMessage) }
        }
    }

    private lateinit var geminiLive: GeminiLiveService

    init {
        geminiLive = GeminiLiveService(audioPlayer, geminiLiveCallback)

        // Setup audio playback worker
        audioPlayer.startPlaybackWorker(viewModelScope)
        audioPlayer.onSpeakingStateChanged = { speaking ->
            _uiState.update { it.copy(isSpeaking = speaking) }
        }
        audioPlayer.onAmplitude = { amp ->
            if (_uiState.value.isSpeaking) {
                _uiState.update { it.copy(amplitude = amp) }
            }
        }

        // Check API key configuration
        val key = geminiLive.getApiKey()
        _uiState.update {
            it.copy(
                apiKeyConfigured = key.isNotBlank(),
                connectionStatus = if (key.isNotBlank()) "Connecting to Gemini Live..." else "API Key Needed"
            )
        }

        // Seed demo contacts initially for seamless testing
        actionBridge.seedContactsIntoDevice()
        loadContacts()

        // Welcome greeting from Arushi
        val welcomeMsg = ChatMessage(
            sender = MessageSender.ARUSHI,
            text = "Namaste! I am Arushi, your voice companion. I understand and speak Hindi, English, Hinglish, Marathi, and more. Try saying 'WhatsApp kholo', 'Mummy ko call karo', or 'Open YouTube'!",
            detectedLanguage = "Hinglish"
        )
        _uiState.update { it.copy(messages = listOf(welcomeMsg)) }

        // Start Gemini Live connection
        if (key.isNotBlank()) {
            geminiLive.connectLive(viewModelScope)
        }
    }

    fun loadContacts() {
        val list = actionBridge.loadAllContacts()
        _uiState.update { it.copy(contacts = list) }
    }

    fun toggleMicrophone() {
        // If Arushi is currently speaking, user tap acts as an instant interrupt!
        if (_uiState.value.isSpeaking) {
            interruptSpeaking()
            return
        }

        if (_uiState.value.isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val started = audioRecorder.startRecording(
            scope = viewModelScope,
            onAudioChunk = { chunk ->
                geminiLive.sendAudioChunk(chunk)
            },
            onAmplitude = { amp ->
                if (_uiState.value.isRecording) {
                    _uiState.update { it.copy(amplitude = amp) }
                }
            }
        )

        if (started) {
            _uiState.update { it.copy(isRecording = true, errorMessage = null) }
        } else {
            _uiState.update {
                it.copy(
                    errorMessage = "Microphone permission is required. Please grant RECORD_AUDIO."
                )
            }
        }
    }

    private fun stopRecording() {
        audioRecorder.stopRecording()
        _uiState.update { it.copy(isRecording = false, amplitude = 0f) }
    }

    /**
     * Interrupts Arushi while speaking (Test case #10)
     */
    fun interruptSpeaking() {
        audioPlayer.interrupt()
        _uiState.update {
            it.copy(
                isSpeaking = false,
                amplitude = 0f,
                currentStreamingText = ""
            )
        }
    }

    /**
     * Sends user prompt, executing actions and generating speech
     */
    fun sendPrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return

        // Interrupt any current speech
        interruptSpeaking()

        val detectedLang = detectLanguage(trimmed)
        _uiState.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    sender = MessageSender.USER,
                    text = trimmed,
                    detectedLanguage = detectedLang
                ),
                detectedLanguage = "Detected: $detectedLang",
                errorMessage = null
            )
        }

        // Try Gemini Live WebSocket first if connected
        val liveSent = geminiLive.sendTextLive(trimmed)
        if (!liveSent) {
            // Execute via REST audio interaction
            viewModelScope.launch {
                _uiState.update { it.copy(connectionStatus = "Arushi is thinking...") }

                val restRes = geminiLive.executeRestInteraction(trimmed) { toolName, args ->
                    val result = executeBridgeAction(toolName, args)
                    result.details
                }

                when (restRes) {
                    is RestResult.Success -> {
                        _uiState.update {
                            it.copy(
                                connectionStatus = if (geminiLive.isLiveConnected()) "Live Connected" else "Live Standby",
                                messages = it.messages + ChatMessage(
                                    sender = MessageSender.ARUSHI,
                                    text = restRes.spokenText,
                                    detectedLanguage = detectLanguage(restRes.spokenText)
                                )
                            )
                        }
                    }
                    is RestResult.Error -> {
                        _uiState.update {
                            it.copy(
                                connectionStatus = "Ready",
                                errorMessage = restRes.errorMessage
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Executes bridge actions on the Android device and updates UI
     */
    fun executeBridgeAction(toolName: String, args: Map<String, Any?>): ActionExecution {
        val execution: ActionExecution = when (toolName) {
            "openWhatsApp" -> {
                val phone = args["phoneNumber"] as? String
                val message = args["message"] as? String
                when (val res = actionBridge.openWhatsApp(phone, message)) {
                    is BridgeResult.Success -> ActionExecution(
                        toolName = "openWhatsApp",
                        summary = res.summary,
                        details = res.details,
                        status = ActionStatus.SUCCESS,
                        iconName = "whatsapp"
                    )
                    is BridgeResult.Failure -> ActionExecution(
                        toolName = "openWhatsApp",
                        summary = "WhatsApp Failed",
                        details = res.reason,
                        status = ActionStatus.FAILED,
                        iconName = "whatsapp"
                    )
                    else -> ActionExecution("openWhatsApp", "WhatsApp Action", "Done")
                }
            }

            "openApp" -> {
                val appName = args["appName"] as? String ?: "app"
                when (val res = actionBridge.openApp(appName)) {
                    is BridgeResult.Success -> ActionExecution(
                        toolName = "openApp",
                        summary = res.summary,
                        details = res.details,
                        status = ActionStatus.SUCCESS,
                        iconName = "apps"
                    )
                    is BridgeResult.Failure -> ActionExecution(
                        toolName = "openApp",
                        summary = "App Not Found",
                        details = res.reason,
                        status = ActionStatus.WARNING,
                        iconName = "apps"
                    )
                    else -> ActionExecution("openApp", "Open $appName", "Executed")
                }
            }

            "makeCall" -> {
                val phone = args["phoneNumber"] as? String ?: ""
                when (val res = actionBridge.makeCall(phone)) {
                    is BridgeResult.Success -> ActionExecution(
                        toolName = "makeCall",
                        summary = res.summary,
                        details = res.details,
                        status = ActionStatus.SUCCESS,
                        iconName = "phone"
                    )
                    is BridgeResult.Failure -> ActionExecution(
                        toolName = "makeCall",
                        summary = "Call Failed",
                        details = res.reason,
                        status = ActionStatus.FAILED,
                        iconName = "phone"
                    )
                    else -> ActionExecution("makeCall", "Make Call", "Executed")
                }
            }

            "callContact" -> {
                val contactName = args["contactName"] as? String ?: ""
                when (val res = actionBridge.callContact(contactName)) {
                    is BridgeResult.Success -> ActionExecution(
                        toolName = "callContact",
                        summary = res.summary,
                        details = res.details,
                        status = ActionStatus.SUCCESS,
                        iconName = "person"
                    )
                    is BridgeResult.MultipleContacts -> ActionExecution(
                        toolName = "callContact",
                        summary = "Clarification Required",
                        details = res.question,
                        status = ActionStatus.WARNING,
                        iconName = "group"
                    )
                    is BridgeResult.Failure -> ActionExecution(
                        toolName = "callContact",
                        summary = "Contact Not Found",
                        details = res.reason,
                        status = ActionStatus.FAILED,
                        iconName = "person_off"
                    )
                }
            }

            "openUrl" -> {
                val url = args["url"] as? String ?: ""
                when (val res = actionBridge.openUrl(url)) {
                    is BridgeResult.Success -> ActionExecution(
                        toolName = "openUrl",
                        summary = res.summary,
                        details = res.details,
                        status = ActionStatus.SUCCESS,
                        iconName = "link"
                    )
                    is BridgeResult.Failure -> ActionExecution(
                        toolName = "openUrl",
                        summary = "Failed to Open Link",
                        details = res.reason,
                        status = ActionStatus.FAILED,
                        iconName = "link"
                    )
                    else -> ActionExecution("openUrl", "Opened Link", url)
                }
            }

            else -> ActionExecution(
                toolName = toolName,
                summary = "Action Executed",
                details = "Executed tool $toolName with parameters: $args",
                status = ActionStatus.SUCCESS
            )
        }

        // Add action execution to message feed
        _uiState.update {
            it.copy(
                activeAction = execution,
                messages = it.messages + ChatMessage(
                    sender = MessageSender.SYSTEM,
                    text = "${execution.summary}: ${execution.details}",
                    actionExecution = execution
                )
            )
        }

        return execution
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun setShowContactsSheet(show: Boolean) {
        if (show) loadContacts()
        _uiState.update { it.copy(showContactsSheet = show) }
    }

    /**
     * Seeds demo contacts into memory and system database
     */
    fun seedContacts() {
        actionBridge.seedContactsIntoDevice()
        loadContacts()
    }

    /**
     * Heuristic for UI language badge
     */
    private fun detectLanguage(text: String): String {
        val t = text.lowercase()
        // Check unicode scripts
        val devanagariCount = text.count { it in '\u0900'..'\u097F' }
        val bengaliCount = text.count { it in '\u0980'..'\u09FF' }
        val gujaratiCount = text.count { it in '\u0A80'..'\u0AFF' }
        val tamilCount = text.count { it in '\u0B80'..'\u0BFF' }
        val teluguCount = text.count { it in '\u0C00'..'\u0C7F' }
        val punjabiCount = text.count { it in '\u0A00'..'\u0A7F' }

        if (devanagariCount > 2) return "Hindi (हिन्दी)"
        if (bengaliCount > 2) return "Bengali (বাংলা)"
        if (gujaratiCount > 2) return "Gujarati (ગુજરાતી)"
        if (tamilCount > 2) return "Tamil (தமிழ்)"
        if (teluguCount > 2) return "Telugu (తెలుగు)"
        if (punjabiCount > 2) return "Punjabi (ਪੰਜਾਬੀ)"

        val hinglishTokens = listOf(
            "mein", "karo", "kholo", "chalao", "lagao", "baat", "bolo", "kaun",
            "kya", "shukriya", "namaste", "dhanyawad", "sunao", "batao", "kaise",
            "apna", "meri", "mera", "mere", "raha", "rahi", "hoon", "hain", "mummy"
        )
        val matchesHinglish = hinglishTokens.any { t.contains(it) }
        if (matchesHinglish) return "Hinglish (हिंग्लिश)"

        return "English"
    }

    override fun onCleared() {
        super.onCleared()
        audioRecorder.stopRecording()
        audioPlayer.release()
        geminiLive.disconnect()
    }
}
