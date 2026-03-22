package com.xiaodoubao.chat.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xiaodoubao.chat.data.model.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "chat_messages")

class MessageDataStore(private val context: Context) {

    private fun messagesKey(sessionId: String) = stringPreferencesKey("messages_$sessionId")

    fun getMessages(sessionId: String): Flow<List<Message>> {
        return context.dataStore.data.map { preferences ->
            val json = preferences[messagesKey(sessionId)] ?: "[]"
            parseMessages(json)
        }
    }

    suspend fun saveMessages(sessionId: String, messages: List<Message>) {
        context.dataStore.edit { preferences ->
            preferences[messagesKey(sessionId)] = serializeMessages(messages)
        }
    }

    suspend fun appendMessage(sessionId: String, message: Message) {
        context.dataStore.edit { preferences ->
            val key = messagesKey(sessionId)
            val existing = preferences[key] ?: "[]"
            val arr = JSONArray(existing)
            arr.put(messageToJson(message))
            preferences[key] = arr.toString()
        }
    }

    suspend fun updateMessage(sessionId: String, updatedMessage: Message) {
        context.dataStore.edit { preferences ->
            val key = messagesKey(sessionId)
            val existing = preferences[key] ?: "[]"
            val arr = JSONArray(existing)
            val newArr = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.getString("id") == updatedMessage.id) {
                    newArr.put(messageToJson(updatedMessage))
                } else {
                    newArr.put(obj)
                }
            }
            preferences[key] = newArr.toString()
        }
    }

    private fun serializeMessages(messages: List<Message>): String {
        val arr = JSONArray()
        messages.forEach { arr.put(messageToJson(it)) }
        return arr.toString()
    }

    private fun parseMessages(json: String): List<Message> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val obj = arr.getJSONObject(i)
                    Message(
                        id = obj.getString("id"),
                        sessionId = obj.getString("sessionId"),
                        text = obj.getString("text"),
                        isUser = obj.getBoolean("isUser"),
                        timestamp = obj.getLong("timestamp"),
                        isFailed = obj.optBoolean("isFailed", false),
                        errorDetail = obj.optString("errorDetail").takeIf { it.isNotEmpty() && it != "null" },
                        retryCount = obj.optInt("retryCount", 0),
                        isAck = obj.optBoolean("isAck", false)
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun messageToJson(msg: Message): JSONObject {
        return JSONObject().apply {
            put("id", msg.id)
            put("sessionId", msg.sessionId)
            put("text", msg.text)
            put("isUser", msg.isUser)
            put("timestamp", msg.timestamp)
            put("isFailed", msg.isFailed)
            msg.errorDetail?.let { put("errorDetail", it) }
            put("retryCount", msg.retryCount)
            put("isAck", msg.isAck)
        }
    }
}
