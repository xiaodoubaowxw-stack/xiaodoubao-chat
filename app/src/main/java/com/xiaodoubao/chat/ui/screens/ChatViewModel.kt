package com.xiaodoubao.chat.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaodoubao.chat.data.local.MessageDataStore
import com.xiaodoubao.chat.data.model.Message
import com.xiaodoubao.chat.data.model.SendResult
import com.xiaodoubao.chat.data.model.Session
import com.xiaodoubao.chat.data.repository.ChatRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val SERVER_URL = "http://124.156.194.65:5568"
        private const val USER_ID = "41DEFE0E0D9F56B1A5355E6EC9B5CDCA"
        private const val POLL_INTERVAL_MS = 3000L
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
            _currentSession.value = session
            _messages.value = emptyList()
            lastMessageId = null
            repository.getMessages(session.id).collectLatest { msgs ->
                val filtered = msgs.filter { !it.isAck }
                _messages.value = filtered
                lastMessageId = filtered.lastOrNull()?.id
            }
        }
        startPolling(session.id)
    }

    private fun startPolling(sessionId: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                val newMessages = repository.pollMessages(sessionId, lastMessageId)
                if (newMessages.isNotEmpty()) {
                    val filtered = newMessages.filter { !it.isAck }
                    if (filtered.isNotEmpty()) {
                        val currentMsgs = _messages.value.toMutableList()
                        filtered.forEach { newMsg ->
                            if (currentMsgs.none { it.id == newMsg.id }) {
                                currentMsgs.add(newMsg)
                                repository.appendMessage(sessionId, newMsg)
                            }
                        }
                        _messages.value = currentMsgs
                        lastMessageId = currentMsgs.lastOrNull()?.id
                    }
                }
            }
        }
    }

    fun sendMessage(text: String) {
        val session = _currentSession.value ?: return
        viewModelScope.launch {
            _isSending.value = true
            val userMessage = Message.userMessage(session.id, text)
            repository.appendMessage(session.id, userMessage)
            _messages.value = _messages.value + userMessage

            repository.sendMessage(userMessage).collectLatest { result ->
                when (result) {
                    is SendResult.Success -> {
                        val updated = _messages.value.map {
                            if (it.id == userMessage.id) it.copy(id = result.messageId) else it
                        }
                        _messages.value = updated
                        lastMessageId = result.messageId
                        _isSending.value = false
                    }
                    is SendResult.Error -> {
                        val updated = _messages.value.map {
                            if (it.id == userMessage.id) it.copy(isFailed = true, errorDetail = result.error) else it
                        }
                        _messages.value = updated
                        repository.saveMessages(session.id, updated)
                        _isSending.value = false
                    }
                    is SendResult.Retrying -> {
                        val updated = _messages.value.map {
                            if (it.id == userMessage.id) it.copy(retryCount = result.attempt) else it
                        }
                        _messages.value = updated
                    }
                }
            }
        }
    }

    fun retryMessage(message: Message) {
        val session = _currentSession.value ?: return
        viewModelScope.launch {
            _isSending.value = true
            repository.retryMessage(message).collectLatest { result ->
                when (result) {
                    is SendResult.Success -> {
                        val updated = _messages.value.map {
                            if (it.id == message.id) it.copy(isFailed = false, errorDetail = null, retryCount = 0, id = result.messageId) else it
                        }
                        _messages.value = updated
                        lastMessageId = result.messageId
                        repository.saveMessages(session.id, updated)
                        _isSending.value = false
                    }
                    is SendResult.Error -> {
                        val updated = _messages.value.map {
                            if (it.id == message.id) it.copy(isFailed = true, errorDetail = result.error, retryCount = 0) else it
                        }
                        _messages.value = updated
                        repository.saveMessages(session.id, updated)
                        _isSending.value = false
                    }
                    is SendResult.Retrying -> {
                        val updated = _messages.value.map {
                            if (it.id == message.id) it.copy(retryCount = result.attempt) else it
                        }
                        _messages.value = updated
                    }
                }
            }
        }
    }

    fun showLogDialog(message: Message) {
        viewModelScope.launch {
            _logDialogEvent.emit(message)
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }
}
