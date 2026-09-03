package com.ytsaver.app.applock

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether the app is currently locked behind biometric/PIN
 * authentication - like a privacy app-locker. Locks automatically whenever
 * the whole app (not just one screen) goes to the background, so switching
 * away and back always re-prompts.
 */
object AppLockManager {

    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var observerRegistered = false

    fun unlock() {
        _locked.value = false
    }

    /** Call once, e.g. from Application.onCreate(). */
    fun registerLifecycleObserver() {
        if (observerRegistered) return
        observerRegistered = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                _locked.value = true
            }
        })
    }
}
