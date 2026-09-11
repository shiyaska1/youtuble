package com.mobicareapp.applock

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Locks the whole app (not just one screen) whenever it goes to the background — registered once
 * against the process lifecycle rather than a single Activity's, so backgrounding from any screen
 * re-locks it, and rotation/config changes (which stop/start an Activity without the app itself
 * leaving the foreground) don't trigger a false re-lock.
 */
object AppLockManager {
    private var observerRegistered = false
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val clearSuppression = Runnable { suppressed = false }
    @Volatile private var suppressed = false

    fun unlock() {
        _locked.value = false
    }

    /**
     * Launching a system picker like the document scanner (Google Play Services' own scanning
     * Activity, started via IntentSender) briefly backgrounds this app's process — without this,
     * that one stop/start cycle re-locks the app while the scan is still in progress, which
     * swaps the whole nav graph out for the lock screen and disposes the ActivityResultLauncher
     * waiting on the scanner's result, silently dropping the scanned pages once unlocked. Call
     * this right before launching that kind of request; it clears itself on the very next
     * backgrounding (the expected case) or after a timeout if that never happens, so it can't
     * leave the app permanently unlockable.
     */
    fun suppressNextLock() {
        suppressed = true
        mainHandler.removeCallbacks(clearSuppression)
        mainHandler.postDelayed(clearSuppression, 15_000)
    }

    fun registerLifecycleObserver() {
        if (observerRegistered) return
        observerRegistered = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                if (suppressed) {
                    suppressed = false
                    mainHandler.removeCallbacks(clearSuppression)
                } else {
                    _locked.value = true
                }
            }
        })
    }
}
