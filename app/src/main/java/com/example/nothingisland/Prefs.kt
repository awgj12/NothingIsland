package com.example.nothingisland

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

object Prefs {
    private val D = mapOf(
        "posX" to 0, "posY" to 0,
        "idleW" to 90, "idleH" to 24, "liveW" to 170, "liveH" to 30,
        "width" to 300, "radius" to 30, "bannerTop" to 28, "textScale" to 100, "iconSize" to 38,
        "bannerX" to 0, "bannerY" to 0, "secs" to 4
    )
    fun sp(c: Context) = c.getSharedPreferences("island", Context.MODE_PRIVATE)
    fun int(c: Context, k: String) = sp(c).getInt(k, D[k] ?: 0)
    fun on(c: Context, k: String) = sp(c).getBoolean(k, true)
    fun flag(c: Context, k: String, def: Boolean) = sp(c).getBoolean(k, def)
    fun accent(c: Context) = sp(c).getInt("accent", 0xFFD71921.toInt())
    fun muted(c: Context): Set<String> = sp(c).getStringSet("muted", null) ?: emptySet()

    private fun light(c: Context) = flag(c, "light", false)
    fun bg(c: Context) = if (light(c)) Color.WHITE else Color.BLACK
    fun fg(c: Context) = if (light(c)) Color.BLACK else Color.WHITE
    fun dim(c: Context) = Color.parseColor(if (light(c)) "#6B6B6B" else "#9A9A9A")
    fun edge(c: Context) = Color.parseColor(if (light(c)) "#D4D4D4" else "#000000")
    fun off(c: Context) = Color.parseColor(if (light(c)) "#DADADA" else "#333333")
    fun surface(c: Context) = Color.parseColor(if (light(c)) "#EFEFEF" else "#000000")

    private var cached: Typeface? = null
    fun font(c: Context): Typeface {
        cached?.let { return it }
        val id = c.resources.getIdentifier("dotfont", "font", c.packageName)
        val t = (if (id != 0) ResourcesCompat.getFont(c, id) else null) ?: Typeface.MONOSPACE
        cached = t
        return t
    }
}
