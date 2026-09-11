package com.mobicareapp.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import android.util.Rational
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.ui.PlayerView
import com.mobicareapp.data.MediaAccess
import com.mobicareapp.data.SavedMedia
import com.mobicareapp.playback.LiveEqAudioProcessor
import com.mobicareapp.process.NoiseFilterProcessor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    queue: List<SavedMedia>,
    startIndex: Int,
    initialLoop: Boolean,
    onBack: () -> Unit,
    onFullscreenChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var isFullscreen by remember { mutableStateOf(false) }
    val isInPip by PipState.isInPip.collectAsState()
    var playbackError by remember { mutableStateOf<String?>(null) }
    var loopEnabled by remember { mutableStateOf(initialLoop) }
    var currentTitle by remember { mutableStateOf(queue.getOrNull(startIndex)?.caption.orEmpty()) }
    var showLiveEq by remember { mutableStateOf(false) }
    val liveEqBandGains = remember { mutableStateListOf(*DoubleArray(NoiseFilterProcessor.MANUAL_BAND_FREQUENCIES_HZ.size) { 0.0 }.toTypedArray()) }
    val liveEq = remember { LiveEqAudioProcessor() }

    val exoPlayer = remember {
        // Routes playback audio through liveEq so the EQ panel's sliders change what you're
        // hearing immediately — the standard ExoPlayer.Builder(context) constructor has no way to
        // insert a custom AudioProcessor into the pipeline, only a custom RenderersFactory does.
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(liveEq))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
        }
        ExoPlayer.Builder(context, renderersFactory).build().apply {
            setMediaItems(queue.map { it.toMediaItem() }, startIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0)), 0)
            repeatMode = if (initialLoop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
            playWhenReady = true
            prepare()
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        // PiP requires the aspect ratio to stay within [1:2.39, 2.39:1].
                        val ratio = (videoSize.width.toFloat() / videoSize.height).coerceIn(1f / 2.39f, 2.39f)
                        PipState.aspectRatio = Rational((ratio * 1000).toInt(), 1000)
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    currentTitle = mediaItem?.mediaMetadata?.title?.toString().orEmpty()
                }

                override fun onPlayerError(error: PlaybackException) {
                    // Naming the actual reason matters: an imported video that this phone can't
                    // decode needs converting, which is a completely different fix from a file
                    // that genuinely isn't there any more.
                    playbackError = when (error.errorCode) {
                        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                            "This file is missing — it may have been moved or deleted outside the app."
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                            "This phone can't play this video's format. Try \"Convert to MP4\" from the ⋮ menu in Library."
                        else -> "Couldn't play this file (${error.errorCodeName})."
                    }
                }
            })
        }
    }

    DisposableEffect(Unit) {
        PipState.player = exoPlayer
        PipState.isVideoActive.value = true
        onDispose {
            PipState.isVideoActive.value = false
            PipState.player = null
            exoPlayer.release()
        }
    }

    fun setFullscreen(enabled: Boolean) {
        isFullscreen = enabled
        onFullscreenChange(enabled)
        val window = activity?.window ?: return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun toggleLoop() {
        loopEnabled = !loopEnabled
        exoPlayer.repeatMode = if (loopEnabled) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    }

    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (isFullscreen) setFullscreen(false)
        }
    }

    BackHandler(enabled = isFullscreen) { setFullscreen(false) }

    Scaffold(
        topBar = {
            if (!isFullscreen && !isInPip) {
                TopAppBar(
                    title = { Text(currentTitle, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showLiveEq = !showLiveEq }) {
                            Icon(
                                Icons.Default.GraphicEq,
                                contentDescription = if (showLiveEq) "Hide live filter" else "Live noise filter",
                                tint = if (showLiveEq) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (queue.size > 1) {
                            IconButton(onClick = { toggleLoop() }) {
                                Icon(
                                    if (loopEnabled) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                    contentDescription = if (loopEnabled) "Loop on" else "Loop off",
                                    tint = if (loopEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = if (isFullscreen || isInPip) Modifier.fillMaxSize() else Modifier.fillMaxSize().padding(padding)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        keepScreenOn = true
                        setFullscreenButtonClickListener { fullscreenWanted ->
                            setFullscreen(fullscreenWanted)
                        }
                    }
                }
            )
            playbackError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )
            }

            if (showLiveEq && !isFullscreen && !isInPip) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("Live noise filter", style = MaterialTheme.typography.titleSmall)
                    NoiseFilterProcessor.MANUAL_BAND_FREQUENCIES_HZ.forEachIndexed { index, freq ->
                        Text(
                            "${formatEqFrequency(freq)} — ${liveEqBandGains[index].toInt()} dB",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Slider(
                            value = liveEqBandGains[index].toFloat(),
                            onValueChange = { newValue ->
                                liveEqBandGains[index] = newValue.toDouble()
                                liveEq.setBandGainsDb(liveEqBandGains.toDoubleArray())
                            },
                            valueRange = -40f..0f
                        )
                    }
                }
            }
        }
    }
}

private fun formatEqFrequency(hz: Int): String = if (hz >= 1000) "${hz / 1000}kHz" else "${hz}Hz"

private fun SavedMedia.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(MediaAccess.playableUriString(filePath))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(caption).build())
        .build()

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
