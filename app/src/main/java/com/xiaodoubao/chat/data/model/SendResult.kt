package com.xiaodoubao.chat.data.model

sealed class SendResult {
    data class Success(val messageId: String) : SendResult()
    data class Error(val message: Message, val error: String) : SendResult()
    data class Retrying(val message: Message, val attempt: Int, val nextDelayMs: Long) : SendResult()
}
