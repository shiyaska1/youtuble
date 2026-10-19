package com.mobicareapp.handwriting

import android.content.Context

/** The pen colour, the text colour and the text font chosen for the handwriting pad (remembered between visits). */
object HandwritingStyle {
    private const val PREFS = "hw_style"
    private const val KEY_INK = "ink"
    private const val KEY_TEXT = "text"
    private const val KEY_NORMAL_FONT = "normal_font"

    /** Colour name and ARGB value; 0 stands for the default dark blue. */
    val PALETTE: List<Pair<String, Int>> = listOf(
        "Dark blue" to 0,
        "Black" to 0xFF000000.toInt(),
        "Blue" to 0xFF1565C0.toInt(),
        "Red" to 0xFFC62828.toInt(),
        "Green" to 0xFF2E7D32.toInt(),
        "Purple" to 0xFF6A1B9A.toInt(),
        "Orange" to 0xFFEF6C00.toInt(),
        "Brown" to 0xFF5D4037.toInt()
    )

    fun inkColor(context: Context): Int = prefs(context).getInt(KEY_INK, 0)
    fun setInkColor(context: Context, color: Int) = prefs(context).edit().putInt(KEY_INK, color).apply()

    fun textColor(context: Context): Int = prefs(context).getInt(KEY_TEXT, 0)
    fun setTextColor(context: Context, color: Int) = prefs(context).edit().putInt(KEY_TEXT, color).apply()

    /** True for an ordinary (sans-serif) font instead of the handwriting-style one. */
    fun normalFont(context: Context): Boolean = prefs(context).getBoolean(KEY_NORMAL_FONT, false)
    fun setNormalFont(context: Context, normal: Boolean) = prefs(context).edit().putBoolean(KEY_NORMAL_FONT, normal).apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
