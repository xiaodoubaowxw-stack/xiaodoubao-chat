package com.xiaodoubao.chat.ui.screens

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaodoubao.chat.data.local.MessageDataStore
import com.xiaodoubao.chat.data.model.Message
import com.xiaodoubao.chat.data.model.SendResult
import com.xiaodoubao.chat.data.model.Session
import com.xiaodoubao.chat.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val SERVER_URL = "http://124.156.194.65:5568"
        private const val USER_ID = "41DEFE0E0D9F56B1A5355E6EC9B5CDCA"
        private const val POLL_INTERVAL_MS = 3000L
        private const val TAG = "ChatViewModel"
    }

    private val dataStore = MessageDataStore(application)
    private val repository = ChatRepository(USER_ID, SERVER_URL, dataStore)

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _currentSession = MutableStateFlow<Session?>(null)
    val currentSession: StateFlow<Session?> = _currentSession.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _logDialogEvent = MutableSharedFlow<Message>()
    val logDialogEvent = _logDialogEvent.asSharedFlow()

    private var pollJob: Job? = null
    private var lastMessageId: String? = null

    fun setSession(session: Session) {
        viewModelScope.launch {
            try {
                _currentSession.value = session
                _messages.value = emptyList()
                lastMessageId = null
                repository.getMessages(session.id).collectLatest { msgs ->
                    val filtered = msgs.filter { msg -> !msg.isAck }
                    _messages.value = filtered
                    lastMessageId = filtered.lastOrNull()?.id
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading messages", e)
            }
        }
        startPolling(session.id)
    }

    private fun startPolling(sessionId: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                try {
                    delay(POLL_INTERVAL_MS)
                    val newMessages = repository.pollMessages(sessionId, lastMessageId)
                    if (newMessages.isNotEmpty()) {
                        val filtered = newMessages.filter { msg -> !msg.isAck }
                        if (filtered.isNotEmpty()) {
                            val currentMsgs = _messages.value.toMutableList()
                            filtered.forEach { newMsg ->
                                if (currentMsgs.none { it.id == newMsg.id }) {
                                    currentMsgs.add(newMsg)
                                }
                            }
                            _messages.value = currentMsgs
                            lastMessageId = currentMsgs.lastOrNull()?.id
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Polling error", e)
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        if (_isSending.value) return
        val session = _currentSession.value ?: return

        val userMessage = Message.userMessage(session.id, text)

        // Update UI immediately on main thread
        _messages.value = _messages.value + userMessage

        viewModelScope.launch {
            try {
                _isSending.value = true
                repository.sendMessage(userMessage).collectLatest { result ->
                    when (result) {
                        is SendResult.Success -> {
                            val currentList = _messages.value.toMutableList()
                            val idx = currentList.indexOfFirst { it.id == userMessage.id }
                            if (idx >= 0) {
                                val updated = currentList[idx].copy(id = result.messageId)
                                currentList[idx] = updated
                                _messages.value = currentList
                                lastMessageId = result.messageId
                            }
                            _isSending.value = false
                        }
                        is SendResult.Error -> {
                            val currentList = _messages.value.toMutableList()
                            val idx = currentList.indexOfFirst { it.id == userMessage.id }
                            if (idx >= 0) {
                                val updated = currentList[idx].copy(
                                    isFailed = true,
                                    errorDetail = result.error,
                                    retryCount = 0
                                )
                                currentList[idx] = updated
                                _messages.value = currentList
                            }
                            withContext(Dispatchers.IO) {
                                try {
                                    repository.saveMessages(session.id, _messages.value)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Save error", e)
                                }
                            }
                            _isSending.value = false
                        }
                        is SendResult.Retrying -> {
                            val currentList = _messages.value.toMutableList()
                            val idx = currentList.indexOfFirst { it.id == userMessage.id }
                            if (idx >= 0) {
                                val updated = currentList[idx].copy(retryCount = result.attempt)
                                currentList[idx] = updated
                                _messages.value = currentList
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage exception", e)
                _isSending.value = false
                val currentList = _messages.value.toMutableList()
                val idx = currentList.indexOfFirst { it.id == userMessage.id }
                if (idx >= 0) {
                    currentList[idx] = currentList[idx].copy(isFailed = true, errorDetail = e.message)
                    _messages.value = currentList
                }
            }
        }
    }

    fun retryMessage(message: Message) {
        if (_isSending.value) return
        val session = _currentSession.value ?: return

        viewModelScope.launch {
            try {
                _isSending.value = true
                repository.retryMessage(message).collectLatest { result ->
                    when (result) {
                        is SendResult.Success -> {
                            val currentList = _messages.value.toMutableList()
                            val idx = currentList.indexOfFirst { it.id == message.id }
                            if (idx >= 0) {
                                currentList[idx] = currentList[idx].copy(
                                    isFailed = false,
                                    errorDetail = null,
                                    retryCount = 0,
                                    id = result.messageId
                                )
                                _messages.value = currentList
                                lastMessageId = result.messageId
                            }
                            withContext(Dispatchers.IO) {
                                try {
                                    repository.saveMessages(session.id, _messages.value)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Save error", e)
                                }
                            }
                            _isSending.value = false
                        }
                        is SendResult.Error -> {
                            val currentList = _messages.value.toMutableList()
                            val idx = currentList.indexOfFirst { it.id == message.id }
                            if (idx >= 0) {
                                currentList[idx] = currentList[idx].copy(
                                    isFailed = true,
                                    errorDetail = result.error,
                                    retryCount = 0
                                )
                                _messages.value = currentList
                            }
                            withContext(Dispatchers.IO) {
                                try {
                                    repository.saveMessages(session.id, _messages.value)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Save error", e)
                                }
                            }
                            _isSending.value = false
                        }
                        is SendResult.Retrying -> {
                            val currentList = _messages.value.toMutableList()
                            val idx = currentList.indexOfFirst { it.id == message.id }
                            if (idx >= 0) {
                                currentList[idx] = currentList[idx].copy(retryCount = result.attempt)
                                _messages.value = currentList
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "retryMessage exception", e)
                _isSending.value = false
            }
        }
    }

    fun showLogDialog(message: Message) {
        viewModelScope.launch {
            try {
                _logDialogEvent.emit(message)
            } catch (e: Exception) {
                Log.e(TAG, "showLogDialog error", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }
}
