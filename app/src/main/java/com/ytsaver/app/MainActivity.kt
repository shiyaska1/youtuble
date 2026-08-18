package com.ytsaver.app

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
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
import androidx.compose.runtime.collectAsState
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
import com.ytsaver.app.ui.player.PipState
import com.ytsaver.app.ui.player.PlayerScreen
import com.ytsaver.app.ui.theme.YtSaverTheme

class MainActivity : ComponentActivity() {

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_PIP_PLAY_PAUSE) {
                PipState.player?.let { player -> if (player.isPlaying) player.pause() else player.play() }
                updatePipParams()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter(ACTION_PIP_PLAY_PAUSE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(pipActionReceiver, filter)
        }
        setContent {
            YtSaverTheme {
                AppRoot()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(pipActionReceiver) }
    }

    /** Same behavior as the YouTube app: leaving while a video plays floats it instead of stopping it. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (PipState.isVideoActive.value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { enterPictureInPictureMode(buildPipParams()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipState.isInPip.value = isInPictureInPictureMode
    }

    private fun buildPipParams(): PictureInPictureParams {
        val isPlaying = PipState.player?.isPlaying == true
        val icon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val label = if (isPlaying) "Pause" else "Play"
        val actionIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_PIP_PLAY_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val action = RemoteAction(Icon.createWithResource(this, icon), label, label, actionIntent)
        return PictureInPictureParams.Builder()
            .setAspectRatio(PipState.aspectRatio)
            .setActions(listOf(action))
            .build()
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
            setPictureInPictureParams(buildPipParams())
        }
    }

    private companion object {
        const val ACTION_PIP_PLAY_PAUSE = "com.ytsaver.app.PIP_PLAY_PAUSE"
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
    val isInPip by PipState.isInPip.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        if (bannerVisible && !isInPip) {
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
