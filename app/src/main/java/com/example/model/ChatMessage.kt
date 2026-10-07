package com.example.model

enum class MessageSender {
    USER,
    ARUSHI,
    SYSTEM
}

enum class ActionStatus {
    PENDING,
    SUCCESS,
    WARNING,
    FAILED
}

data class ActionExecution(
    val toolName: String,
    val summary: String,
    val details: String,
    val status: ActionStatus = ActionStatus.SUCCESS,
    val iconName: String = "launch"
)

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val detectedLanguage: String? = null,
    val actionExecution: ActionExecution? = null
)
