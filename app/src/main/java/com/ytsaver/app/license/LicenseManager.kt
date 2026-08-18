package com.ytsaver.app.license

import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * Local trial gate: the app works freely for [TRIAL_DURATION_MILLIS] after the
 * first launch, then requires an unlock key to keep going. State is stored in
 * SharedPreferences so it survives process death but resets on reinstall.
 */
object LicenseManager {
    private const val PREFS_NAME = "ytsaver_license"
    private const val KEY_FIRST_LAUNCH = "first_launch_time"
    private const val KEY_UNLOCKED = "unlocked"

    private val TRIAL_DURATION_MILLIS = TimeUnit.DAYS.toMillis(30)

    // Key required to unlock the app once the trial has expired.
    private const val UNLOCK_KEY = "YTSAVER-UNLOCK-2026"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun firstLaunchTime(context: Context): Long {
        val p = prefs(context)
        val existing = p.getLong(KEY_FIRST_LAUNCH, -1L)
        if (existing != -1L) return existing
        val now = System.currentTimeMillis()
        p.edit().putLong(KEY_FIRST_LAUNCH, now).apply()
        return now
    }

    fun isUnlocked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_UNLOCKED, false)

    private fun isTrialExpired(context: Context): Boolean {
        val elapsed = System.currentTimeMillis() - firstLaunchTime(context)
        return elapsed >= TRIAL_DURATION_MILLIS
    }

    fun requiresKey(context: Context): Boolean =
        isTrialExpired(context) && !isUnlocked(context)

    /** Whole days left in the trial, 0 once it has expired. */
    fun daysRemaining(context: Context): Int {
        val elapsed = System.currentTimeMillis() - firstLaunchTime(context)
        val remainingMillis = (TRIAL_DURATION_MILLIS - elapsed).coerceAtLeast(0)
        return TimeUnit.MILLISECONDS.toDays(remainingMillis).toInt()
    }

    fun tryUnlock(context: Context, enteredKey: String): Boolean {
        val valid = enteredKey.trim() == UNLOCK_KEY
        if (valid) {
            prefs(context).edit().putBoolean(KEY_UNLOCKED, true).apply()
        }
        return valid
    }
}
