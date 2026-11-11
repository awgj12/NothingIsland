package com.example.nothingisland

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var col: LinearLayout
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val red = Color.parseColor("#D71921")

    private fun label(t: String, sz: Float = 14f) = col.addView(TextView(this).apply {
        text = t; textSize = sz; setTextColor(Color.WHITE); typeface = Prefs.font(this@MainActivity)
        setPadding(0, dp(14), 0, dp(4))
    })

    private fun btn(t: String, onClick: () -> Unit) = col.addView(Button(this).apply {
        text = t; setTextColor(Color.WHITE); typeface = Prefs.font(this@MainActivity)
        setBackgroundColor(red); setOnClickListener { onClick() }
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    private fun slider(name: String, key: String, min: Int, max: Int, unit: String) {
        val t = TextView(this).apply {
            setTextColor(Color.WHITE); typeface = Prefs.font(this@MainActivity); setPadding(0, dp(12), 0, 0)
        }
        fun upd(v: Int) { t.text = "$name: $v$unit" }
        upd(Prefs.int(this, key))
        col.addView(t)
        col.addView(SeekBar(this).apply {
            this.max = max - min; progress = Prefs.int(this@MainActivity, key) - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    upd(p + min); Prefs.sp(this@MainActivity).edit().putInt(key, p + min).apply()
                    IslandListener.instance?.refreshIdle()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
    }

    private fun toggle(name: String, key: String) = col.addView(Switch(this).apply {
        text = name; setTextColor(Color.WHITE); typeface = Prefs.font(this@MainActivity)
        isChecked = Prefs.on(this@MainActivity, key)
        setOnCheckedChangeListener { _, on -> Prefs.sp(this@MainActivity).edit().putBoolean(key, on).apply(); IslandListener.instance?.refreshIdle() }
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(56), dp(24), dp(72))
        }
        label("NOTHING ISLAND", 26f)
        label("SETUP")
        btn("1. ALLOW DISPLAY OVER APPS") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("2. ALLOW NOTIFICATION ACCESS") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        btn("TEST ISLAND") { IslandListener.instance?.test() }

        label("FEATURES")
        toggle("Always-on small pill", "idle")
        toggle("Notifications", "notif")
        toggle("Music", "media")
        toggle("Calls, alarms, timers", "calls")
        toggle("Charging", "charge")

        label("LOOK")
        slider("Small pill width", "idleW", 60, 140, " dp")
        slider("Expanded width", "width", 220, 380, " dp")
        slider("Move up / down", "nudge", -15, 15, " dp")
        slider("Show time", "secs", 2, 10, " s")
        label("Accent color")
        val row = LinearLayout(this)
        listOf("RED" to 0xFFD71921.toInt(), "WHITE" to 0xFFFFFFFF.toInt(), "GREEN" to 0xFF00C853.toInt()).forEach { (n, c) ->
            row.addView(Button(this).apply {
                text = n; typeface = Prefs.font(this@MainActivity); setBackgroundColor(c)
                setTextColor(if (c == 0xFFFFFFFF.toInt()) Color.BLACK else Color.WHITE)
                setOnClickListener { Prefs.sp(this@MainActivity).edit().putInt("accent", c).apply(); IslandListener.instance?.refreshIdle() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        }
        col.addView(row)
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(col) })
    }
}
