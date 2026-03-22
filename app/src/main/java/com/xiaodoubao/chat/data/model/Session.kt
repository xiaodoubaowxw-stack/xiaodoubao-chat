package com.xiaodoubao.chat.data.model

import androidx.compose.ui.graphics.Color

data class Session(
    val id: String,
    val name: String,
    val icon: String,
    val color: Long
) {
    companion object {
        val SESSIONS = listOf(
            Session("health", "健康", "🏥", 0xFF4CAF50),
            Session("legal", "法律", "⚖️", 0xFF795548),
            Session("medical", "医疗", "💊", 0xFFF44336),
            Session("academic", "学术", "📚", 0xFF2196F3),
            Session("programming", "编程", "💻", 0xFF9C27B0),
            Session("gaming", "游戏", "🎮", 0xFFFF9800),
            Session("english", "英语", "🇬🇧", 0xFF607D8B),
            Session("social", "社交", "💬", 0xFF00BCD4)
        )

        fun getById(id: String): Session = SESSIONS.find { it.id == id } ?: SESSIONS[0]
    }
}
