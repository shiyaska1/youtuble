package com.mobicareapp

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
import androidx.compose.material.icons.filled.FiberManualRecord
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
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mobicareapp.applock.AppLockManager
import com.mobicareapp.applock.AppLockScreen
import com.mobicareapp.applock.canUseAppLock
import com.mobicareapp.data.SavedMedia
import com.mobicareapp.handwriting.HandwritingStartup
import com.mobicareapp.handwriting.HandwritingStore
import com.mobicareapp.ui.home.HomeScreen
import com.mobicareapp.ui.library.LibraryScreen
import com.mobicareapp.ui.player.PipState
import com.mobicareapp.ui.browser.BrowserScreen
import com.mobicareapp.ui.browser.openInBrowse
import com.mobicareapp.ui.player.PlayerScreen
import com.mobicareapp.record.PendingQuickRecordAction
import com.mobicareapp.ui.record.RecordScreen
import com.mobicareapp.ui.scan.ScanScreen
import com.mobicareapp.ui.theme.YtSaverTheme

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
        AppLockManager.registerLifecycleObserver()
        handleQuickActionIntent(intent)
        // A fresh launch (not a rotation/recreation, and not a record shortcut) opens the handwriting notepad for quick notes.
        if (savedInstanceState == null && intent?.getStringExtra(EXTRA_QUICK_ACTION) == null &&
            HandwritingStore.isOpenAtStartup(this)
        ) {
            HandwritingStartup.request()
        }
        setContent {
            YtSaverTheme {
                AppRoot(this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // "standard" launch mode means a shortcut tap while the app is already running creates a
        // new Activity instance (onCreate runs again) rather than calling this — handled here too
        // anyway, in case that ever changes.
        handleQuickActionIntent(intent)
    }

    private fun handleQuickActionIntent(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_QUICK_ACTION)) {
            "record_audio" -> PendingQuickRecordAction.set(PendingQuickRecordAction.Action.AUDIO)
            "record_video" -> PendingQuickRecordAction.set(PendingQuickRecordAction.Action.VIDEO)
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
        const val ACTION_PIP_PLAY_PAUSE = "com.mobicareapp.PIP_PLAY_PAUSE"
        const val EXTRA_QUICK_ACTION = "quick_action"
    }
}

@Composable
private fun AppRoot(activity: FragmentActivity) {
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val batteryOptimizationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestUnrestrictedBatteryIfNeeded(context, batteryOptimizationLauncher)
    }

    val appLocked by AppLockManager.locked.collectAsState()
    if (appLocked && canUseAppLock(activity)) {
        AppLockScreen(activity, onUnlocked = { AppLockManager.unlock() })
        return
    }

    val navController = rememberNavController()
    var videoRequest by remember { mutableStateOf<VideoQueueRequest?>(null) }
    // Hoisted above "main" (rather than local to MainScaffold) so it survives that composable
    // being torn down and rebuilt when the player/browser destinations are pushed on top of it —
    // otherwise it always reset to tab 0 on the way back, regardless of which tab was open before
    // a video was opened.
    var selectedTab by remember { mutableStateOf(0) }
    // A shortcut tap (long-press the app icon → "Record Audio"/"Record Video") sets this before
    // this composition even starts — jump straight to the Record tab so RecordScreen's own
    // LaunchedEffect can see it and start recording immediately, no extra taps needed.
    val pendingQuickAction by PendingQuickRecordAction.pending.collectAsState()
    LaunchedEffect(pendingQuickAction) {
        if (pendingQuickAction != null) selectedTab = 3
    }
    // Handwriting notepad as the start-up screen: it lives in the Scan tab, which opens it as soon as it appears.
    LaunchedEffect(Unit) {
        if (HandwritingStartup.isRequested() && PendingQuickRecordAction.pending.value == null) selectedTab = 2
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            NavHost(navController, startDestination = "main") {
                composable("main") {
                    MainScaffold(
                        selectedTab = selectedTab,
                        onTabSelected = { selectedTab = it },
                        onOpenVideo = { queue, startIndex, loop ->
                            videoRequest = VideoQueueRequest(queue, startIndex, loop)
                            navController.navigate("player")
                        },
                        onOpenBrowser = { url ->
                            if (url != null) openInBrowse(url)
                            navController.navigate("browser")
                        }
                    )
                }
                composable("browser") {
                    BrowserScreen(
                        onBack = { navController.popBackStack() },
                        onOpenVideo = { queue, startIndex, loop ->
                            videoRequest = VideoQueueRequest(queue, startIndex, loop)
                            navController.navigate("player")
                        }
                    )
                }
                composable("player") {
                    val request = videoRequest
                    if (request != null) {
                        PlayerScreen(
                            queue = request.queue,
                            startIndex = request.startIndex,
                            initialLoop = request.loop,
                            onBack = {
                                // Wherever it was opened from, watching a video and going back
                                // should land on Library, not silently return to whatever tab
                                // (often Home/Save) happened to be selected beforehand.
                                selectedTab = 1
                                navController.popBackStack()
                            },
                            onFullscreenChange = { }
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
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    onOpenVideo: (List<SavedMedia>, Int, Boolean) -> Unit,
    onOpenBrowser: (String?) -> Unit
) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { onTabSelected(0) },
                    icon = { Icon(Icons.Default.Add, contentDescription = "Save") },
                    label = { Text("Save") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { onTabSelected(1) },
                    icon = { Icon(Icons.Default.List, contentDescription = "Library") },
                    label = { Text("Library") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { onTabSelected(2) },
                    icon = { Icon(Icons.Default.DocumentScanner, contentDescription = "Scan") },
                    label = { Text("Scan") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { onTabSelected(3) },
                    icon = { Icon(Icons.Default.FiberManualRecord, contentDescription = "Record") },
                    label = { Text("Record") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                0 -> HomeScreen(onOpenBrowser = onOpenBrowser)
                1 -> LibraryScreen(onOpenVideo = onOpenVideo)
                2 -> ScanScreen()
                else -> RecordScreen()
            }
        }
    }
}

/**
 * Background audio playback, multi-file downloads, and especially voice-activated/always-listen
 * recording get killed by aggressive OEM battery managers unless the app is exempted from
 * Doze/App Standby — this is required for those features to actually survive the app being
 * swiped away from Recents, so it asks on every launch until actually granted, not just once ever
 * (a one-time ask that gets dismissed or missed would otherwise never come back).
 */
private fun requestUnrestrictedBatteryIfNeeded(context: Context, launcher: ActivityResultLauncher<Intent>) {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
        runCatching { launcher.launch(intent) }
    }
}
