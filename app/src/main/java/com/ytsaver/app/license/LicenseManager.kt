package com.ytsaver.app.license

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Local trial gate: the app works freely for [TRIAL_DURATION_MILLIS] after the
 * first launch, then requires an unlock key to keep going. The key is bound
 * to the device's Android ID via a keyed hash, so a key generated for one
 * device won't unlock another. State is stored in SharedPreferences so it
 * survives process death but resets on reinstall.
 *
 * To activate a device: read its Device ID off the lock screen, then run
 * `tools/generate_key.py <DEVICE_ID>` (uses the same SECRET_SALT below) to
 * get the matching key.
 */
object LicenseManager {
    private const val PREFS_NAME = "ytsaver_license"
    private const val KEY_FIRST_LAUNCH = "first_launch_time"
    private const val KEY_UNLOCKED = "unlocked"

    private val TRIAL_DURATION_MILLIS = TimeUnit.DAYS.toMillis(30)

    // Shared secret between this app and tools/generate_key.py. Change it in
    // both places together if you need to invalidate previously issued keys.
    private const val SECRET_SALT = "ytsaver-secret-salt-2026"

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

    @SuppressLint("HardwareIds")
    fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"

    /** The activation key that unlocks [deviceId], formatted as XXXX-XXXX-XXXX-XXXX. */
    fun expectedKeyFor(deviceId: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SECRET_SALT.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hex = mac.doFinal(deviceId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }
            .take(16)
        return hex.chunked(4).joinToString("-")
    }

    fun tryUnlock(context: Context, enteredKey: String): Boolean {
        val expected = expectedKeyFor(deviceId(context)).replace("-", "")
        val entered = enteredKey.trim().replace("-", "").replace(" ", "").uppercase()
        val valid = entered == expected
        if (valid) {
            prefs(context).edit().putBoolean(KEY_UNLOCKED, true).apply()
        }
        return valid
    }
}
