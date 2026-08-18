package com.ytsaver.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ytsaver.app.data.SavedMedia
import com.ytsaver.app.ui.home.HomeScreen
import com.ytsaver.app.ui.library.LibraryScreen
import com.ytsaver.app.ui.nav.ContactBanner
import com.ytsaver.app.ui.player.PlayerScreen
import com.ytsaver.app.ui.theme.YtSaverTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            YtSaverTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val batteryOptimizationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestUnrestrictedBatteryOnce(context, batteryOptimizationLauncher)
    }

    val navController = rememberNavController()
    var openVideo by remember { mutableStateOf<SavedMedia?>(null) }
    var bannerVisible by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (bannerVisible) {
            ContactBanner()
        }
        Box(modifier = Modifier.weight(1f)) {
            NavHost(navController, startDestination = "main") {
                composable("main") {
                    MainScaffold(
                        onOpenVideo = { video ->
                            openVideo = video
                            navController.navigate("player")
                        }
                    )
                }
                composable("player") {
                    val video = openVideo
                    if (video != null) {
                        PlayerScreen(
                            filePath = video.filePath,
                            caption = video.caption,
                            onBack = { navController.popBackStack() },
                            onFullscreenChange = { fullscreen -> bannerVisible = !fullscreen }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MainScaffold(onOpenVideo: (SavedMedia) -> Unit) {
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Add, contentDescription = "Save") },
                    label = { Text("Save") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.List, contentDescription = "Library") },
                    label = { Text("Library") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                0 -> HomeScreen()
                else -> LibraryScreen(onOpenVideo = onOpenVideo)
            }
        }
    }
}

/**
 * Background audio playback and multi-file downloads get killed by
 * aggressive OEM battery managers unless the app is exempted from Doze/App
 * Standby. Ask once, the first time the app is opened.
 */
private fun requestUnrestrictedBatteryOnce(context: Context, launcher: ActivityResultLauncher<Intent>) {
    val prefs = context.getSharedPreferences("ytsaver_prefs", Context.MODE_PRIVATE)
    if (prefs.getBoolean("asked_battery_optimization", false)) return

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
        runCatching { launcher.launch(intent) }
    }
    prefs.edit().putBoolean("asked_battery_optimization", true).apply()
}
