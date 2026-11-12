package com.example.nothingisland

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var col: LinearLayout
    private lateinit var f: Typeface
    private lateinit var stOverlay: TextView
    private lateinit var stNotif: TextView
    private val red = Color.parseColor("#D71921")
    private val surface = Color.parseColor("#141414")
    private val sliders = HashMap<String, Pair<SeekBar, Int>>()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String, sz: Float, color: Int) = TextView(this).apply {
        text = t; textSize = sz; setTextColor(color); typeface = f
    }

    private fun section(t: String) = col.addView(label(t.uppercase(), 12f, red).apply {
        setPadding(dp(4), dp(28), 0, dp(8)); letterSpacing = 0.2f
    })

    private fun group(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(14))
        background = GradientDrawable().apply { setColor(surface); cornerRadius = dp(22).toFloat() }
    }.also { col.addView(it, LinearLayout.LayoutParams(-1, -2)) }

    private fun btn(p: LinearLayout, t: String, primary: Boolean = false, weight: Float = 0f, onClick: () -> Unit): Button {
        val b = Button(this).apply {
            text = t; typeface = f; setTextColor(Color.WHITE); isAllCaps = false; stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                if (primary) setColor(red) else { setColor(Color.TRANSPARENT); setStroke(dp(1), Color.parseColor("#555555")) }
            }
            setOnClickListener { onClick() }
        }
        val lp = if (weight > 0f) LinearLayout.LayoutParams(0, -2, weight) else LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(dp(3), dp(4), dp(3), dp(4))
        p.addView(b, lp)
        return b
    }

    private val hh = Handler(Looper.getMainLooper())

    /** Button that keeps repeating while you hold it */
    private fun holdBtn(p: LinearLayout, t: String, act: () -> Unit) {
        val b = btn(p, t, false, 1f) {}
        var rep: Runnable? = null
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    act()
                    val r = object : Runnable { override fun run() { act(); hh.postDelayed(this, 50) } }
                    rep = r; hh.postDelayed(r, 350)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { rep?.let { hh.removeCallbacks(it) }; rep = null }
            }
            true
        }
    }

    private fun slider(p: LinearLayout, name: String, key: String, min: Int, max: Int, unit: String) {
        val t = label("$name: ${Prefs.int(this, key)}$unit", 13f, Color.WHITE).apply { setPadding(0, dp(10), 0, 0) }
        val bar = SeekBar(this)
        bar.max = max - min
        bar.progress = (Prefs.int(this, key) - min).coerceIn(0, max - min)
        bar.progressTintList = ColorStateList.valueOf(red)
        bar.thumbTintList = ColorStateList.valueOf(red)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                Prefs.sp(this@MainActivity).edit().putInt(key, v + min).apply()
                t.text = "$name: ${v + min}$unit"
                IslandListener.instance?.refreshIdle()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        sliders[key] = Pair(bar, min)
        p.addView(t); p.addView(bar)
    }

    private fun setVal(key: String, v: Int) {
        val (bar, min) = sliders[key] ?: return
        bar.progress = (v - min).coerceIn(0, bar.max)
    }

    private fun nudge(key: String, d: Int) = setVal(key, Prefs.int(this, key) + d)

    private fun toggle(p: LinearLayout, name: String, key: String, def: Boolean = true) = p.addView(Switch(this).apply {
        text = name; setTextColor(Color.WHITE); typeface = f
        isChecked = Prefs.flag(this@MainActivity, key, def)
        thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(red, Color.GRAY))
        trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.parseColor("#5A0A0E"), Color.parseColor("#333333")))
        setOnCheckedChangeListener { _, on ->
            Prefs.sp(this@MainActivity).edit().putBoolean(key, on).apply()
            IslandListener.instance?.refreshIdle()
        }
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    private fun spacer() = View(this)
    private fun row(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun testIsland(exp: Boolean) {
        val s = IslandListener.instance
        if (s == null) Toast.makeText(this, "Allow notification access first", Toast.LENGTH_SHORT).show() else s.test(exp)
    }

    private fun snapToCamera() {
        val r = try { windowManager.currentWindowMetrics.windowInsets.displayCutout?.boundingRects?.firstOrNull() } catch (e: Exception) { null }
        if (r == null) {
            Toast.makeText(this, "Camera not detected. Use the arrows.", Toast.LENGTH_SHORT).show(); return
        }
        val d = resources.displayMetrics.density
        setVal("posY", Math.round(r.centerY() / d - Prefs.int(this, "idleH") / 2f))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        f = Prefs.font(this)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(56), dp(20), dp(80)) }
        col.addView(label("NOTHING ISLAND", 28f, Color.WHITE))
        col.addView(label("dynamic island, nothing style", 12f, Color.parseColor("#9A9A9A")))

        section("Setup")
        val g1 = group()
        stOverlay = label("", 13f, Color.WHITE); stNotif = label("", 13f, Color.WHITE)
        g1.addView(stOverlay); g1.addView(stNotif)
        btn(g1, "ALLOW DISPLAY OVER APPS", true) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn(g1, "ALLOW NOTIFICATION ACCESS", true) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        btn(g1, "KEEP RUNNING (BATTERY)") { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        val tr = row()
        btn(tr, "TEST", false, 1f) { testIsland(false) }
        btn(tr, "TEST EXPANDED", false, 1f) { testIsland(true) }
        g1.addView(tr)

        section("Position")
        val g2 = group()
        g2.addView(label("Tap an arrow to move 1 dp. Hold it to move fast. Go up until it sits on your camera.", 12f, Color.parseColor("#9A9A9A")))
        val r1 = row(); r1.addView(spacer(), LinearLayout.LayoutParams(0, 1, 1f)); holdBtn(r1, "UP") { nudge("posY", -1) }
        r1.addView(spacer(), LinearLayout.LayoutParams(0, 1, 1f)); g2.addView(r1)
        val r2 = row()
        holdBtn(r2, "LEFT") { nudge("posX", -1) }
        btn(r2, "RESET", false, 1f) { setVal("posX", 0); setVal("posY", 0) }
        holdBtn(r2, "RIGHT") { nudge("posX", 1) }
        g2.addView(r2)
        val r3 = row(); r3.addView(spacer(), LinearLayout.LayoutParams(0, 1, 1f)); holdBtn(r3, "DOWN") { nudge("posY", 1) }
        r3.addView(spacer(), LinearLayout.LayoutParams(0, 1, 1f)); g2.addView(r3)
        btn(g2, "SNAP TO CAMERA HEIGHT") { snapToCamera() }
        slider(g2, "Left / right", "posX", -200, 200, " dp")
        slider(g2, "Up / down", "posY", -40, 100, " dp")

        section("Look")
        val g3 = group()
        toggle(g3, "Always-on small pill", "idle")
        toggle(g3, "Light style (white)", "light", false)
        toggle(g3, "Haptic tick", "haptic")
        slider(g3, "Small pill width", "idleW", 50, 200, " dp")
        slider(g3, "Small pill height", "idleH", 16, 48, " dp")
        slider(g3, "Expanded width", "width", 220, 380, " dp")
        slider(g3, "Show time", "secs", 2, 10, " s")
        g3.addView(label("Accent color", 13f, Color.WHITE).apply { setPadding(0, dp(14), 0, dp(6)) })
        val cr = row()
        listOf(0xFFD71921, 0xFFFFFFFF, 0xFF00C853, 0xFFFFD600, 0xFF2979FF).forEach { l ->
            val c = l.toInt()
            cr.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
                setOnClickListener {
                    Prefs.sp(this@MainActivity).edit().putInt("accent", c).apply()
                    IslandListener.instance?.refreshIdle()
                }
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
        }
        g3.addView(cr)

        section("Features")
        val g4 = group()
        toggle(g4, "Notifications", "notif")
        toggle(g4, "Music + album art", "media")
        toggle(g4, "Calls, alarms, timers", "calls")
        toggle(g4, "Charging", "charge")
        g4.addView(label("Tap: open. Hold: expand. Swipe up: close.", 12f, Color.parseColor("#9A9A9A")).apply { setPadding(0, dp(12), 0, 0) })

        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(col) })
    }

    override fun onResume() {
        super.onResume()
        val ov = Settings.canDrawOverlays(this)
        val nt = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        fun st(v: TextView, name: String, ok: Boolean) {
            v.text = (if (ok) "ON   " else "OFF  ") + name
            v.setTextColor(if (ok) Color.parseColor("#00C853") else red)
        }
        st(stOverlay, "Display over apps", ov)
        st(stNotif, "Notification access", nt)
        IslandListener.instance?.refreshIdle()
    }
}
