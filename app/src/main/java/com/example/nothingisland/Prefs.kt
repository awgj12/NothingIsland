package com.example.nothingisland

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

object Prefs {
    private val D = mapOf("width" to 300, "top" to 10, "secs" to 4, "idleW" to 90, "nudge" to 0)
    fun sp(c: Context) = c.getSharedPreferences("island", Context.MODE_PRIVATE)
    fun int(c: Context, k: String) = sp(c).getInt(k, D[k] ?: 0)
    fun on(c: Context, k: String) = sp(c).getBoolean(k, true)
    fun accent(c: Context) = sp(c).getInt("accent", 0xFFD71921.toInt())
    fun font(c: Context): Typeface {
        val id = c.resources.getIdentifier("dotfont", "font", c.packageName)
        return (if (id != 0) ResourcesCompat.getFont(c, id) else null) ?: Typeface.MONOSPACE
    }
}
