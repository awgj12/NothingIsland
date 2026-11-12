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
    private lateinit var stMusic: TextView
    private lateinit var appsBox: LinearLayout
    private val red = Color.parseColor("#D71921")
    private val grey = Color.parseColor("#9A9A9A")
    private val surface = Color.parseColor("#141414")
    private val sliders = HashMap<String, Pair<SeekBar, Int>>()
    private val hh = Handler(Looper.getMainLooper())
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

    private fun hint(p: LinearLayout, t: String) = p.addView(label(t, 12f, grey).apply { setPadding(0, dp(4), 0, dp(4)) })

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

    /** Button that keeps repeating while you hold it */
    private fun holdBtn(p: LinearLayout, t: String, act: () -> Unit) {
        val b = btn(p, t, false, 1f) {}
        var rep: Runnable? = null
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    act()
                    val r = object : Runnable { override fun run() { act(); hh.postDelayed(this, 45) } }
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
                val svc = IslandListener.instance
                when (key) {
                    "posX", "posY", "idleW", "liveW" -> svc?.moveIdle()
                    "idleH", "liveH" -> svc?.refreshIdle()
                }
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

    private fun sw(name: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = name; setTextColor(Color.WHITE); typeface = f; isChecked = checked
        thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(red, Color.GRAY))
        trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.parseColor("#5A0A0E"), Color.parseColor("#333333")))
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    private fun toggle(p: LinearLayout, name: String, key: String, def: Boolean = true) = p.addView(
        sw(name, Prefs.flag(this, key, def)) { on ->
            Prefs.sp(this).edit().putBoolean(key, on).apply(); IslandListener.instance?.refreshIdle()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    private fun spacer() = View(this)
    private fun row(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun testIsland(exp: Boolean) {
        val s = IslandListener.instance
        if (s == null) toast("Allow notification access first") else s.test(exp)
    }

    private fun snapToCamera() {
        val r = try { windowManager.currentWindowMetrics.windowInsets.displayCutout?.boundingRects?.firstOrNull() } catch (e: Exception) { null }
        if (r == null) { toast("Camera not detected. Use the arrows."); return }
        val d = resources.displayMetrics.density
        setVal("posY", Math.round(r.centerY() / d - Prefs.int(this, "idleH") / 2f))
    }

    /** Apps that have sent a notification. Switch one off to mute it. */
    private fun fillApps() {
        appsBox.removeAllViews()
        val seen = (Prefs.sp(this).getStringSet("seen", null) ?: emptySet()).sorted()
        if (seen.isEmpty()) { hint(appsBox, "Apps show up here after they send a notification."); return }
        for (pkg in seen) {
            val name = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
            appsBox.addView(sw(name, pkg !in Prefs.muted(this)) { on ->
                val m = HashSet<String>(Prefs.muted(this))
                if (on) m.remove(pkg) else m.add(pkg)
                Prefs.sp(this).edit().putStringSet("muted", m).apply()
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        f = Prefs.font(this)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(56), dp(20), dp(90)) }
        col.addView(label("NOTHING ISLAND", 28f, Color.WHITE))
        col.addView(label("dynamic island, nothing style", 12f, grey))

        // ---------- SETUP ----------
        section("Setup")
        val g1 = group()
        stOverlay = label("", 13f, Color.WHITE); stNotif = label("", 13f, Color.WHITE)
        stMusic = label("", 12f, grey).apply { setPadding(0, dp(4), 0, dp(4)) }
        g1.addView(stOverlay); g1.addView(stNotif); g1.addView(stMusic)
        btn(g1, "ALLOW DISPLAY OVER APPS", true) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn(g1, "ALLOW NOTIFICATION ACCESS", true) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        btn(g1, "KEEP RUNNING (BATTERY)") { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        val tr = row()
        btn(tr, "TEST", false, 1f) { testIsland(false) }
        btn(tr, "TEST EXPANDED", false, 1f) { testIsland(true) }
        g1.addView(tr)
        btn(g1, "TEST MUSIC") {
            val s = IslandListener.instance
            if (s == null) toast("Allow notification access first")
            else if (!s.testMusicSafe()) toast("Play a song in your music app first")
        }

        // ---------- PILL POSITION ----------
        section("Pill position")
        val g2 = group()
        hint(g2, "Tap an arrow to move 1 dp. Hold it to move fast.")
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
        slider(g2, "Left / right", "posX", -300, 300, " dp")
        slider(g2, "Up / down", "posY", -60, 200, " dp")

        // ---------- PILL SIZE ----------
        section("Pill size")
        val g3 = group()
        toggle(g3, "Always-on pill", "idle")
        toggle(g3, "Accent dot on idle pill", "dot")
        hint(g3, "Idle = nothing happening. Live = music, calls, timers (camera sits in the gap).")
        slider(g3, "Idle width", "idleW", 30, 300, " dp")
        slider(g3, "Idle height", "idleH", 10, 70, " dp")
        slider(g3, "Live width", "liveW", 60, 400, " dp")
        slider(g3, "Live height", "liveH", 10, 80, " dp")

        // ---------- BANNER ----------
        section("Banner (notifications)")
        val g4 = group()
        val br = row()
        btn(br, "TEST", false, 1f) { testIsland(false) }
        btn(br, "EXPANDED", false, 1f) { testIsland(true) }
        g4.addView(br)
        slider(g4, "Width", "width", 150, 420, " dp")
        slider(g4, "Corner roundness", "radius", 4, 60, " dp")
        slider(g4, "Space above content", "bannerTop", 0, 100, " dp")
        slider(g4, "Text size", "textScale", 60, 180, " %")
        slider(g4, "Icon size", "iconSize", 20, 72, " dp")
        slider(g4, "Left / right", "bannerX", -200, 200, " dp")
        slider(g4, "Up / down", "bannerY", -60, 200, " dp")
        slider(g4, "Show time", "secs", 1, 15, " s")

        // ---------- STYLE ----------
        section("Style")
        val g5 = group()
        toggle(g5, "Light style (white)", "light", false)
        toggle(g5, "Haptic tick", "haptic")
        g5.addView(label("Accent color", 13f, Color.WHITE).apply { setPadding(0, dp(14), 0, dp(6)) })
        val cr = row()
        listOf(0xFFD71921, 0xFFFFFFFF, 0xFF00C853, 0xFFFFD600, 0xFF2979FF, 0xFFFF6D00, 0xFFD500F9).forEach { l ->
            val c = l.toInt()
            cr.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
                setOnClickListener {
                    Prefs.sp(this@MainActivity).edit().putInt("accent", c).apply()
                    IslandListener.instance?.refreshIdle()
                }
            }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) })
        }
        g5.addView(cr)

        // ---------- FEATURES ----------
        section("Features")
        val g6 = group()
        toggle(g6, "Notifications (every app)", "notif")
        toggle(g6, "Ongoing notifications", "ongoing")
        toggle(g6, "Music + album art", "media")
        toggle(g6, "Calls, alarms, timers", "calls")
        toggle(g6, "Charging + low battery", "charge")
        toggle(g6, "Ringer mode (silent / vibrate)", "ringer")
        hint(g6, "Tap: open. Hold: expand. Swipe up: close. Swipe left / right: skip songs.")

        // ---------- APPS ----------
        section("Apps")
        appsBox = group()

        // ---------- RESET ----------
        section("Reset")
        val g7 = group()
        btn(g7, "RESET ALL SIZES AND POSITIONS") {
            val seen = Prefs.sp(this).getStringSet("seen", null)
            val muted = Prefs.sp(this).getStringSet("muted", null)
            val e = Prefs.sp(this).edit().clear()
            if (seen != null) e.putStringSet("seen", HashSet<String>(seen))
            if (muted != null) e.putStringSet("muted", HashSet<String>(muted))
            e.apply()
            IslandListener.instance?.refreshIdle()
            recreate()
        }

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
        stMusic.text = "Music apps seen: " + (IslandListener.instance?.mediaInfo() ?: "turn on notification access")
        fillApps()
        IslandListener.instance?.refreshIdle()
    }
}
