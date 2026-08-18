package com.ytsaver.app.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.ytsaver.app.data.SavedMedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import java.io.File

/**
 * Single shared connection to [PlaybackService]'s MediaSession, used for all
 * audio playback (single track or a multi-select loop queue) so it keeps
 * running when the Library/Player screens are closed or the app minimizes.
 */
object PlayerController {

    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller = _controller.asStateFlow()

    private var connecting: ListenableFuture<MediaController>? = null

    private suspend fun get(context: Context): MediaController {
        _controller.value?.let { return it }
        val appContext = context.applicationContext
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = connecting ?: MediaController.Builder(appContext, token).buildAsync().also { connecting = it }
        val mediaController = future.await()
        _controller.value = mediaController
        return mediaController
    }

    suspend fun playQueue(context: Context, items: List<SavedMedia>, loopAll: Boolean, startIndex: Int = 0) {
        if (items.isEmpty()) return
        val c = get(context)
        c.setMediaItems(items.map { it.toMediaItem() }, startIndex.coerceIn(0, items.lastIndex), 0)
        c.repeatMode = if (loopAll) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        c.prepare()
        c.play()
    }

    suspend fun togglePlayPause(context: Context) {
        val c = get(context)
        if (c.isPlaying) c.pause() else c.play()
    }

    suspend fun skipNext(context: Context) = get(context).seekToNextMediaItem()

    suspend fun skipPrevious(context: Context) = get(context).seekToPreviousMediaItem()

    suspend fun stop(context: Context) {
        val c = get(context)
        c.stop()
        c.clearMediaItems()
    }

    private fun SavedMedia.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(File(filePath).toURI().toString())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(caption).build())
            .build()
}
