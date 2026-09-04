package com.mobicareapp.ui.player

import android.util.Rational
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Bridges the currently-open video player (owned by a Compose screen) with
 * MainActivity's Picture-in-Picture lifecycle callbacks, which live outside
 * Compose. MainActivity reads [player]/[aspectRatio] when entering PiP and
 * flips [isInPip] so the screen can hide its normal chrome.
 */
object PipState {
    val isVideoActive = MutableStateFlow(false)
    val isInPip = MutableStateFlow(false)
    var player: ExoPlayer? = null
    var aspectRatio: Rational = Rational(16, 9)
}
