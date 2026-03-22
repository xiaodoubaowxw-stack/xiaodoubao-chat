package com.xiaodoubao.chat.data.repository


import com.xiaodoubao.chat.data.local.MessageDataStore
import com.xiaodoubao.chat.data.model.Message
import com.xiaodoubao.chat.data.model.SendResult
import com.xiaodoubao.chat.data.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ChatRepository(
    private val userId: String,
    private val serverUrl: String,
    private val dataStore: MessageDataStore
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val MAX_RETRIES = 5
        private val BASE_DELAY_MS = 800L
    }

    fun getMessages(sessionId: String): Flow<List<Message>> = dataStore.getMessages(sessionId)

    suspend fun saveMessages(sessionId: String, messages: List<Message>) {
        dataStore.saveMessages(sessionId, messages)
    }

    suspend fun appendMessage(sessionId: String, message: Message) {
        dataStore.appendMessage(sessionId, message)
    }

    fun sendMessage(message: Message): Flow<SendResult> = flow {
        var attempt = 0
        while (attempt < MAX_RETRIES) {
            attempt++
            try {
                val encodedText = java.net.URLEncoder.encode(message.text, "UTF-8")
                val url = "$serverUrl/send?id=$userId&text=$encodedText&session=${message.sessionId}"
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (response.isSuccessful && body.isNotEmpty()) {
                    val json = JSONObject(body)
                    val msgId = json.optString("id", message.id)
                    emit(SendResult.Success(msgId))
                    return@flow
                } else {
                    val error = "HTTP ${response.code}: ${response.message}"
                    if (attempt >= MAX_RETRIES) {
                        emit(SendResult.Error(message, error))
                        return@flow
                    }
                    val delayMs = BASE_DELAY_MS * (1 shl (attempt - 1))
                    emit(SendResult.Retrying(message, attempt, delayMs))
                    delay(delayMs)
                }
            } catch (e: Exception) {
                val error = e.message ?: "Unknown error"
                if (attempt >= MAX_RETRIES) {
                    emit(SendResult.Error(message, error))
                    return@flow
                }
                val delayMs = BASE_DELAY_MS * (1 shl (attempt - 1))
                emit(SendResult.Retrying(message, attempt, delayMs))
                delay(delayMs)
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun pollMessages(sessionId: String, afterMsgId: String?): List<Message> = withContext(Dispatchers.IO) {
        try {
            val url = if (afterMsgId != null) {
                "$serverUrl/poll?id=$userId&after=$afterMsgId"
            } else {
                "$serverUrl/poll?id=$userId"
            }
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.isSuccessful && body.isNotEmpty()) {
                parsePollResponse(body, sessionId)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parsePollResponse(body: String, sessionId: String): List<Message> {
        return try {
            val json = JSONObject(body)
            val messages = json.optJSONArray("messages") ?: JSONArray()
            (0 until messages.length()).mapNotNull { i ->
                try {
                    val obj = messages.getJSONObject(i)
                    val meta = obj.optJSONObject("meta")
                    val isAck = meta?.optBoolean("ack", false) ?: false
                    Message(
                        id = obj.optString("id", "unknown"),
                        sessionId = sessionId,
                        text = obj.optString("text", ""),
                        isUser = false,
                        timestamp = System.currentTimeMillis(),
                        isAck = isAck
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun retryMessage(message: Message): Flow<SendResult> = flow {
        val retriedMessage = message.copy(isFailed = false, errorDetail = null, retryCount = 0)
        var attempt = 0
        while (attempt < MAX_RETRIES) {
            attempt++
            try {
                val encodedText = java.net.URLEncoder.encode(retriedMessage.text, "UTF-8")
                val url = "$serverUrl/send?id=$userId&text=$encodedText&session=${retriedMessage.sessionId}"
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (response.isSuccessful && body.isNotEmpty()) {
                    val json = JSONObject(body)
                    val msgId = json.optString("id", retriedMessage.id)
                    emit(SendResult.Success(msgId))
                    return@flow
                } else {
                    val error = "HTTP ${response.code}: ${response.message}"
                    if (attempt >= MAX_RETRIES) {
                        emit(SendResult.Error(retriedMessage, error))
                        return@flow
                    }
                    val delayMs = BASE_DELAY_MS * (1 shl (attempt - 1))
                    emit(SendResult.Retrying(retriedMessage, attempt, delayMs))
                    delay(delayMs)
                }
            } catch (e: Exception) {
                val error = e.message ?: "Unknown error"
                if (attempt >= MAX_RETRIES) {
                    emit(SendResult.Error(retriedMessage, error))
                    return@flow
                }
                val delayMs = BASE_DELAY_MS * (1 shl (attempt - 1))
                emit(SendResult.Retrying(retriedMessage, attempt, delayMs))
                delay(delayMs)
            }
        }
    }.flowOn(Dispatchers.IO)
}
