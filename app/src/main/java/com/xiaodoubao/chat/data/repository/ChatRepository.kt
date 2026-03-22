package com.xiaodoubao.chat.data.repository

import android.util.Log
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
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class ChatRepository(
    private val userId: String,
    private val serverUrl: String,
    private val dataStore: MessageDataStore
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    companion object {
        private const val MAX_RETRIES = 5
        private val BASE_DELAY_MS = 800L
        private const val TAG = "ChatRepository"
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
            } catch (e: SocketTimeoutException) {
                val error = "连接超时 (attempt $attempt)"
                if (attempt >= MAX_RETRIES) {
                    emit(SendResult.Error(message, error))
                    return@flow
                }
                val delayMs = BASE_DELAY_MS * (1 shl (attempt - 1))
                emit(SendResult.Retrying(message, attempt, delayMs))
                delay(delayMs)
            } catch (e: UnknownHostException) {
                val error = "网络错误：无法连接服务器"
                if (attempt >= MAX_RETRIES) {
                    emit(SendResult.Error(message, error))
                    return@flow
                }
                val delayMs = BASE_DELAY_MS * (1 shl (attempt - 1))
                emit(SendResult.Retrying(message, attempt, delayMs))
                delay(delayMs)
            } catch (e: Exception) {
                val error = e.message ?: "未知错误"
                Log.e(TAG, "Send error attempt $attempt", e)
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

    fun retryMessage(message: Message): Flow<SendResult> = sendMessage(message)

    suspend fun pollMessages(sessionId: String, afterId: String?): List<Message> = withContext(Dispatchers.IO) {
        try {
            val after = afterId ?: "0"
            val url = "$serverUrl/poll?id=$userId&session=$sessionId&after=$after"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: "{\"messages\":[]}"
            parsePollResponse(body, sessionId)
        } catch (e: Exception) {
            Log.e(TAG, "pollMessages error", e)
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
            Log.e(TAG, "parsePollResponse error", e)
            emptyList()
        }
    }
}
