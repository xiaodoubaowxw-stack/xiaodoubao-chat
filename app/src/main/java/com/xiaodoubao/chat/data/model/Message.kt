package com.xiaodoubao.chat.data.model

data class Message(
    val id: String,
    val sessionId: String,
    val text: String,
    val isUser: Boolean,
    val timestamp: Long,
    val isFailed: Boolean = false,
    val errorDetail: String? = null,
    val retryCount: Int = 0,
    val isAck: Boolean = false
) {
    companion object {
        fun fromJson(json: org.json.JSONObject, sessionId: String, isUser: Boolean): Message {
            val id = json.optString("id", System.currentTimeMillis().toString())
            val text = json.optString("text", "")
            val meta = json.optJSONObject("meta")
            val isAck = meta?.optBoolean("ack", false) ?: false
            return Message(
                id = id,
                sessionId = sessionId,
                text = text,
                isUser = isUser,
                timestamp = System.currentTimeMillis(),
                isAck = isAck
            )
        }

        fun userMessage(sessionId: String, text: String): Message {
            return Message(
                id = "user_${System.currentTimeMillis()}_${(0..9999).random()}",
                sessionId = sessionId,
                text = text,
                isUser = true,
                timestamp = System.currentTimeMillis()
            )
        }

        fun botMessage(sessionId: String, text: String, id: String = "bot_${System.currentTimeMillis()}"): Message {
            return Message(
                id = id,
                sessionId = sessionId,
                text = text,
                isUser = false,
                timestamp = System.currentTimeMillis()
            )
        }
    }
}
