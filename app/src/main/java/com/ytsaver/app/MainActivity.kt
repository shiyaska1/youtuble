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
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.SmartDisplay
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
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ytsaver.app.applock.AppLockManager
import com.ytsaver.app.applock.AppLockScreen
import com.ytsaver.app.applock.canUseAppLock
import com.ytsaver.app.data.SavedMedia
import com.ytsaver.app.ui.browser.YoutubeBrowserScreen
import com.ytsaver.app.license.LicenseGateScreen
import com.ytsaver.app.license.LicenseManager
import com.ytsaver.app.ui.home.HomeScreen
import com.ytsaver.app.ui.library.LibraryScreen
import com.ytsaver.app.ui.scan.ImageViewerScreen
import com.ytsaver.app.ui.scan.ScanScreen
import com.ytsaver.app.ui.nav.ContactBanner
import com.ytsaver.app.ui.player.PipState
import com.ytsaver.app.ui.player.PlayerScreen
import com.ytsaver.app.ui.theme.YtSaverTheme

class MainActivity : FragmentActivity() {

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
                AppRoot(activity = this@MainActivity)
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
private fun AppRoot(activity: FragmentActivity) {
    val context = LocalContext.current
    var licensed by remember { mutableStateOf(!LicenseManager.requiresKey(context)) }

    if (!licensed) {
        LicenseGateScreen(onUnlocked = { licensed = true })
        return
    }

    val appLocked by AppLockManager.locked.collectAsState()
    if (appLocked && canUseAppLock(activity)) {
        AppLockScreen(activity = activity, onUnlocked = { AppLockManager.unlock() })
        return
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val batteryOptimizationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Below API 29 downloads are saved with WRITE_EXTERNAL_STORAGE to a real public
        // folder (see DownloadService.legacyMediaDir) instead of MediaStore; that
        // permission needs a runtime request on API 23+.
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M until Build.VERSION_CODES.Q) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        requestUnrestrictedBatteryOnce(context, batteryOptimizationLauncher)
    }

    val navController = rememberNavController()
    var videoRequest by remember { mutableStateOf<VideoQueueRequest?>(null) }
    var imageToView by remember { mutableStateOf<String?>(null) }
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
                        onOpenVideo = { queue, startIndex, loop ->
                            videoRequest = VideoQueueRequest(queue, startIndex, loop)
                            navController.navigate("player")
                        },
                        onOpenImage = { filePath ->
                            imageToView = filePath
                            navController.navigate("image")
                        }
                    )
                }
                composable("image") {
                    imageToView?.let { path ->
                        ImageViewerScreen(filePath = path, onBack = { navController.popBackStack() })
                    }
                }
                composable("player") {
                    val request = videoRequest
                    if (request != null) {
                        PlayerScreen(
                            queue = request.queue,
                            startIndex = request.startIndex,
                            initialLoop = request.loop,
                            onBack = { navController.popBackStack() },
                            onFullscreenChange = { fullscreen -> bannerVisible = !fullscreen }
                        )
                    }
                }
            }
        }
    }
}

private data class VideoQueueRequest(val queue: List<SavedMedia>, val startIndex: Int, val loop: Boolean)

@Composable
private fun MainScaffold(
    onOpenVideo: (List<SavedMedia>, Int, Boolean) -> Unit,
    onOpenImage: (String) -> Unit
) {
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
                    icon = { Icon(Icons.Default.SmartDisplay, contentDescription = "YouTube") },
                    label = { Text("YouTube") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.List, contentDescription = "Library") },
                    label = { Text("Library") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.DocumentScanner, contentDescription = "Scan") },
                    label = { Text("Scan") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                0 -> HomeScreen()
                1 -> YoutubeBrowserScreen()
                2 -> LibraryScreen(onOpenVideo = onOpenVideo, onOpenImage = onOpenImage)
                else -> ScanScreen()
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
