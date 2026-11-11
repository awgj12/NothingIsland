package com.example.nothingisland

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

class Card(
    val key: String, val tag: String, val title: String, val text: String,
    val icon: Drawable?, val pi: PendingIntent?, val sticky: Boolean = false,
    val mc: MediaController? = null, val battery: Int = -1
)

/** Dot-matrix equalizer */
class DotEq(c: Context, private val tint: Int) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
    private val lv = FloatArray(5) { 0.4f }
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 260; repeatCount = ValueAnimator.INFINITE
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationRepeat(a: Animator) {
                for (i in lv.indices) lv[i] = Random.nextFloat()
                invalidate()
            }
        })
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(c: Canvas) {
        val cell = width / 5f
        for (i in 0 until 5) {
            val lit = (lv[i] * 4).toInt() + 1
            for (j in 0 until 5) {
                p.alpha = if (j < lit) 255 else 50
                c.drawCircle(cell * (i + 0.5f), height - cell * (j + 0.5f), cell * 0.3f, p)
            }
        }
    }
}

class IslandListener : NotificationListenerService() {
    private val h = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var idle: View? = null
    private var currentKey = ""
    private var expanded = false
    private val stickies = LinkedHashMap<String, Card>()
    private var controller: MediaController? = null
    private var lastState = -1
    private var lastTitle: String? = null
    private val dismissRun = Runnable { dismiss() }

    companion object { var instance: IslandListener? = null }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun wm() = getSystemService(WINDOW_SERVICE) as WindowManager
    private fun circle(c: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }

    // ---------- lifecycle ----------
    private val sessCb = MediaSessionManager.OnActiveSessionsChangedListener { attach(it) }
    private val mcb = object : MediaController.Callback() {
        override fun onMetadataChanged(m: MediaMetadata?) { maybeShowMedia() }
        override fun onPlaybackStateChanged(s: PlaybackState?) { maybeShowMedia() }
    }
    private val powerRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!Prefs.on(c, "charge")) return
            val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
            val pct = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            present(Card("charge", "CHARGING", "$pct%", "", null, null, battery = pct), false)
        }
    }

    override fun onListenerConnected() {
        instance = this
        registerReceiver(powerRx, IntentFilter(Intent.ACTION_POWER_CONNECTED))
        try {
            val msm = getSystemService(MediaSessionManager::class.java)
            val cn = ComponentName(this, IslandListener::class.java)
            msm.addOnActiveSessionsChangedListener(sessCb, cn)
            attach(msm.getActiveSessions(cn))
        } catch (e: Exception) {}
        showIdle()
    }

    override fun onListenerDisconnected() {
        instance = null
        removeIdle()
        try { unregisterReceiver(powerRx) } catch (e: Exception) {}
        try { getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessCb) } catch (e: Exception) {}
        removeNow()
    }

    // ---------- notifications (also calls / timers) ----------
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (sbn.packageName == packageName || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (n.category == Notification.CATEGORY_TRANSPORT) return // media handled via sessions
        val tag = when (n.category) {
            Notification.CATEGORY_CALL -> "CALL"
            Notification.CATEGORY_ALARM -> "ALARM"
            Notification.CATEGORY_STOPWATCH -> "TIMER"
            else -> ""
        }
        val sticky = tag.isNotEmpty()
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0 && !sticky) return
        if (!Prefs.on(this, if (sticky) "calls" else "notif")) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val icon = try { packageManager.getApplicationIcon(sbn.packageName) } catch (e: Exception) { null }
        val card = Card(sbn.key, tag, title, text, icon, n.contentIntent, sticky)
        h.post { if (sticky) stickies[sbn.key] = card; present(card, false) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        h.post {
            stickies.remove(sbn.key)
            if (currentKey == sbn.key) dismiss()
        }
    }

    // ---------- media ----------
    private fun attach(list: List<MediaController>?) {
        val c = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        if (c?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(mcb)
        controller = c
        c?.registerCallback(mcb, h)
    }

    private fun maybeShowMedia() {
        val c = controller ?: return
        val st = c.playbackState?.state ?: return
        val title = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val changed = st != lastState || title != lastTitle
        lastState = st; lastTitle = title
        if (!changed || st != PlaybackState.STATE_PLAYING || title == null) return
        if (!Prefs.on(this, "media") || (currentKey == "media" && expanded)) return
        val artist = c.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        present(Card("media", "NOW PLAYING", title, artist, null, c.sessionActivity, mc = c), false)
    }

    // ---------- UI ----------
    fun test() = present(Card("test", "", "NOTHING ISLAND", "Tap to open. Hold to expand.", null, null), false)

    private fun tv(t: String, sz: Float, color: Int, lines: Int) = TextView(this).apply {
        text = t; typeface = Prefs.font(this@IslandListener); setTextColor(color); textSize = sz
        maxLines = lines; ellipsize = TextUtils.TruncateAt.END
    }

    private fun present(card: Card, exp: Boolean) {
        if (!Settings.canDrawOverlays(this)) return
        removeIdle()
        removeNow()
        currentKey = card.key; expanded = exp
        val accent = Prefs.accent(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.BLACK); cornerRadius = dp(34).toFloat(); setStroke(dp(1), Color.parseColor("#2A2A2A"))
            }
            elevation = dp(8).toFloat()
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (card.mc != null) {
            row.addView(DotEq(this, accent), LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) })
        } else if (card.icon != null) {
            row.addView(ImageView(this).apply { setImageDrawable(card.icon) },
                LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) })
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (card.tag.isNotEmpty()) col.addView(tv(card.tag, 10f, accent, 1))
        col.addView(tv(card.title.uppercase(), 13f, Color.WHITE, 1))
        if (card.text.isNotEmpty()) col.addView(tv(card.text, 12f, Color.parseColor("#9A9A9A"), if (exp) 4 else 1))
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(View(this).apply { background = circle(accent) },
            LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginStart = dp(10) })
        root.addView(row)

        if (card.battery >= 0) {
            val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            for (i in 0 until 20) bar.addView(View(this).apply {
                background = circle(if (i < card.battery / 5) accent else Color.parseColor("#333333"))
            }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(3) })
            root.addView(bar, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        }
        val mc = card.mc
        if (exp && mc != null) {
            val ctl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun b(label: String, onClick: (TextView) -> Unit) = ctl.addView(
                tv(label, 13f, Color.WHITE, 1).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(6))
                    setOnClickListener { onClick(this) }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            b("<<") { mc.transportControls.skipToPrevious() }
            var playing = mc.playbackState?.state == PlaybackState.STATE_PLAYING
            b(if (playing) "PAUSE" else "PLAY") {
                if (playing) mc.transportControls.pause() else mc.transportControls.play()
                playing = !playing; it.text = if (playing) "PAUSE" else "PLAY"
            }
            b(">>") { mc.transportControls.skipToNext() }
            root.addView(ctl)
        }

        root.setOnClickListener {
            try { card.pi?.send() } catch (e: Exception) {}
            if (!card.sticky) dismiss()
        }
        root.setOnLongClickListener { h.post { present(card, true) }; true }

        val lp = WindowManager.LayoutParams(
            dp(Prefs.int(this, "width")), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = topY(); x = topX(); layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        wm().addView(root, lp)
        view = root

        root.pivotY = 0f
        root.scaleX = 0.25f; root.scaleY = 0.5f; root.alpha = 0f
        root.post { root.pivotX = root.width / 2f }
        root.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(420)
            .setInterpolator(OvershootInterpolator(1.1f)).start()
        if (!card.sticky || exp) h.postDelayed(dismissRun, if (exp) 8000L else Prefs.int(this, "secs") * 1000L)
    }

    private fun removeNow() {
        h.removeCallbacks(dismissRun)
        view?.let { try { wm().removeView(it) } catch (e: Exception) {} }
        view = null; currentKey = ""; expanded = false
    }

    private fun dismiss() {
        h.removeCallbacks(dismissRun)
        val v = view
        view = null; currentKey = ""; expanded = false
        if (v == null) { showSticky(); return }
        v.animate().scaleX(0.25f).scaleY(0.5f).alpha(0f).setDuration(200).withEndAction {
            try { wm().removeView(v) } catch (e: Exception) {}
            if (view == null) showSticky()
        }.start()
    }

    private fun showSticky() {
        val c = stickies.values.lastOrNull()
        if (c != null) present(c, false) else showIdle()
    }

    /** Find the camera hole so the island lines up with it */
    private fun cutout(): android.graphics.Rect? = try {
        wm().currentWindowMetrics.windowInsets.displayCutout?.boundingRects?.firstOrNull()
    } catch (e: Exception) { null }

    private fun topY(): Int {
        val c = cutout()
        val base = if (c != null) c.centerY() - dp(12) else dp(10)
        return base + dp(Prefs.int(this, "nudge"))
    }

    private fun topX(): Int {
        val c = cutout() ?: return 0
        return try { c.centerX() - wm().currentWindowMetrics.bounds.width() / 2 } catch (e: Exception) { 0 }
    }

    /** Small always-on pill around the camera */
    fun refreshIdle() { h.post { removeIdle(); if (view == null) showIdle() } }

    private fun removeIdle() {
        idle?.let { try { wm().removeView(it) } catch (e: Exception) {} }
        idle = null
    }

    private fun showIdle() {
        if (idle != null || view != null) return
        if (!Prefs.on(this, "idle") || !Settings.canDrawOverlays(this)) return
        val pill = android.widget.FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK); cornerRadius = dp(40).toFloat(); setStroke(dp(1), Color.parseColor("#2A2A2A"))
            }
            addView(View(this@IslandListener).apply { background = circle(Prefs.accent(this@IslandListener)) },
                android.widget.FrameLayout.LayoutParams(dp(6), dp(6), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(10) })
        }
        val lp = WindowManager.LayoutParams(
            dp(Prefs.int(this, "idleW")), dp(24),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = topY(); x = topX(); layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        try { wm().addView(pill, lp); idle = pill } catch (e: Exception) {}
    }
}
