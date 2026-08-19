package com.schoolattendance.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.schoolattendance.app.ui.classes.ClassListScreen
import com.schoolattendance.app.ui.detail.ClassDetailScreen
import com.schoolattendance.app.ui.theme.AttendanceTheme
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AttendanceTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val navController = rememberNavController()
    NavHost(navController, startDestination = "classes") {
        composable("classes") {
            ClassListScreen(onOpenClass = { section ->
                val encodedName = URLEncoder.encode(section.name, "UTF-8")
                navController.navigate("class/${section.id}/$encodedName")
            })
        }
        composable("class/{classId}/{className}") { backStackEntry ->
            val classId = backStackEntry.arguments?.getString("classId")?.toLongOrNull() ?: return@composable
            val className = backStackEntry.arguments?.getString("className")
                ?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
            ClassDetailScreen(classId = classId, className = className, onBack = { navController.popBackStack() })
        }
    }
}
