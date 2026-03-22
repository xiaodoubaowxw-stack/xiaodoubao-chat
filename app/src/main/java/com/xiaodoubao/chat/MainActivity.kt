package com.xiaodoubao.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xiaodoubao.chat.data.model.Session
import com.xiaodoubao.chat.ui.screens.ChatScreen
import com.xiaodoubao.chat.ui.screens.ChatViewModel
import com.xiaodoubao.chat.ui.screens.SessionListScreen
import com.xiaodoubao.chat.ui.theme.XiaoDouBaoChatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XiaoDouBaoChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    XiaoDouBaoApp()
                }
            }
        }
    }
}

@Composable
fun XiaoDouBaoApp() {
    val navController = rememberNavController()
    val chatViewModel: ChatViewModel = viewModel()

    NavHost(
        navController = navController,
        startDestination = "session_list"
    ) {
        composable("session_list") {
            SessionListScreen(
                onSessionClick = { session ->
                    chatViewModel.setSession(session)
                    navController.navigate("chat/${session.id}")
                }
            )
        }

        composable(
            route = "chat/{sessionId}",
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: "health"
            val session = Session.getById(sessionId)
            ChatScreen(
                session = session,
                viewModel = chatViewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
