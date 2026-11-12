package com.example.nothingisland

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
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
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.random.Random

class Card(
    val key: String, val tag: String, val title: String, val text: String,
    val icon: Drawable?, val pi: PendingIntent?, val sticky: Boolean = false,
    val mc: MediaController? = null, val battery: Int = -1, val tintIcon: Boolean = false,
    val more: String = ""
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
    private var viewBg: View? = null
    private var viewContent: View? = null
    private var idle: View? = null
    private var idleLive = false
    private var idleAnim: ObjectAnimator? = null
    private var tickRun: Runnable? = null
    private var mediaRun: Runnable? = null
    private var refreshRun: Runnable? = null
    private var currentKey = ""
    private var expanded = false
    private val stickies = LinkedHashMap<String, Card>()
    private val pending = ArrayDeque<Card>()
    private val notifSig = HashMap<String, String>()
    private val labels = HashMap<String, String>()
    private val ctrls = HashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private val lastSig = HashMap<MediaSession.Token, String>()
    private val dismissRun = Runnable { dismiss() }

    companion object { var instance: IslandListener? = null }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun wm() = getSystemService(WINDOW_SERVICE) as WindowManager
    private fun circle(c: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }

    private fun pillBg(radius: Int) = GradientDrawable().apply {
        setColor(Prefs.bg(this@IslandListener)); cornerRadius = dp(radius).toFloat()
        if (Prefs.flag(this@IslandListener, "light", false)) setStroke(dp(1), Prefs.edge(this@IslandListener))
    }

    private fun tick() {
        if (!Prefs.on(this, "haptic")) return
        try { getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)) } catch (e: Exception) {}
    }

    private fun params(w: Int, hgt: Int, touchable: Boolean, dx: Int = 0, dy: Int = 0): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(w, hgt, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = dp(Prefs.int(this@IslandListener, "posX") + dx)
            y = dp(Prefs.int(this@IslandListener, "posY") + dy)
            alpha = 1f
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    // ---------- lifecycle ----------
    private val sessCb = MediaSessionManager.OnActiveSessionsChangedListener { attach(it) }

    private val sysRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED, Intent.ACTION_BATTERY_LOW -> {
                    if (!Prefs.on(c, "charge")) return
                    val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
                    val pct = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val low = i.action == Intent.ACTION_BATTERY_LOW
                    present(Card("charge", if (low) "LOW BATTERY" else "CHARGING", "$pct%", "", null, null, battery = pct), false)
                }
                AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    if (!Prefs.on(c, "ringer")) return
                    val name = when (c.getSystemService(AudioManager::class.java).ringerMode) {
                        AudioManager.RINGER_MODE_SILENT -> "SILENT"
                        AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
                        else -> "RING"
                    }
                    present(Card("ringer", "SOUND", name, "", null, null), false)
                }
            }
        }
    }

    override fun onListenerConnected() {
        instance = this
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_BATTERY_LOW)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        }
        registerReceiver(sysRx, f)
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
        try { unregisterReceiver(sysRx) } catch (e: Exception) {}
        try { getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessCb) } catch (e: Exception) {}
        ctrls.values.forEach { try { it.first.unregisterCallback(it.second) } catch (e: Exception) {} }
        ctrls.clear(); lastSig.clear(); pending.clear()
        removeNow()
    }

    // ---------- notifications: EVERY one, queued so none are missed ----------
    private fun appLabel(pkg: String): String = labels.getOrPut(pkg) {
        try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
        catch (e: Exception) { pkg.substringAfterLast('.') }
    }

    private fun rememberApp(pkg: String) {
        val old = Prefs.sp(this).getStringSet("seen", null)
        if (old != null && pkg in old) return
        val s = HashSet<String>(old ?: emptySet())
        s.add(pkg)
        Prefs.sp(this).edit().putStringSet("seen", s).apply()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        val pkg = sbn.packageName
        if (pkg == packageName) return

        // Music apps (Deezer etc.): grab the media session from the player notification
        if (n.category == Notification.CATEGORY_TRANSPORT) {
            try {
                val tok = n.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
                if (tok != null) h.post {
                    val c = MediaController(this, tok)
                    watch(c); onMedia(c); refreshIdle()
                }
            } catch (e: Exception) {}
            return
        }
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return   // its children are shown on their own

        rememberApp(pkg)
        if (pkg in Prefs.muted(this)) return
        val special = when (n.category) {
            Notification.CATEGORY_CALL -> "CALL"
            Notification.CATEGORY_ALARM -> "ALARM"
            Notification.CATEGORY_STOPWATCH -> "TIMER"
            else -> ""
        }
        val sticky = special.isNotEmpty()
        if (!sticky && (n.flags and Notification.FLAG_ONGOING_EVENT) != 0 && !Prefs.flag(this, "ongoing", true)) return
        if (!Prefs.on(this, if (sticky) "calls" else "notif")) return

        val e = n.extras
        val app = appLabel(pkg)
        val title = (e.getCharSequence(Notification.EXTRA_TITLE) ?: e.getCharSequence(Notification.EXTRA_TITLE_BIG))?.toString() ?: app
        val text = (e.getCharSequence(Notification.EXTRA_TEXT) ?: e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.lastOrNull()
            ?: e.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString() ?: ""
        val more = e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val sig = "$title|$text"
        if (notifSig[sbn.key] == sig) return          // progress bars etc. should not spam
        notifSig[sbn.key] = sig

        val large = try { n.getLargeIcon()?.loadDrawable(this) } catch (ex: Exception) { null }
        val appIcon = if (large == null) try { packageManager.getApplicationIcon(pkg) } catch (ex: Exception) { null } else null
        val small = if (large == null && appIcon == null) try { n.getSmallIcon()?.loadDrawable(this) } catch (ex: Exception) { null } else null
        val card = Card(sbn.key, if (sticky) special else app.uppercase(), title, text, large ?: appIcon ?: small,
            n.contentIntent, sticky, tintIcon = large == null && appIcon == null, more = more)
        h.post { if (sticky) stickies[sbn.key] = card; enqueue(card) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        h.post {
            notifSig.remove(sbn.key)
            stickies.remove(sbn.key)
            pending.removeAll { it.key == sbn.key }
            if (currentKey == sbn.key) dismiss() else refreshIdle()
        }
    }

    private fun enqueue(card: Card) {
        if (view == null) { present(card, false); return }
        if (currentKey == card.key && !expanded) { present(card, false); return }
        pending.removeAll { it.key == card.key }
        pending.addLast(card)
        while (pending.size > 15) pending.removeFirst()
        if (!expanded) { h.removeCallbacks(dismissRun); h.postDelayed(dismissRun, 2200) }   // move along faster when others wait
    }

    // ---------- media: watch EVERY player ----------
    private fun watch(c: MediaController) {
        val t = c.sessionToken
        if (ctrls.containsKey(t)) return
        val cb = object : MediaController.Callback() {
            override fun onMetadataChanged(m: MediaMetadata?) { onMedia(c); refreshIdle() }
            override fun onPlaybackStateChanged(s: PlaybackState?) { onMedia(c); refreshIdle() }
        }
        c.registerCallback(cb, h)
        ctrls[t] = Pair(c, cb)
    }

    private fun attach(list: List<MediaController>?) {
        (list ?: emptyList()).forEach { watch(it) }
        refreshIdle()
    }

    private fun isPlaying(c: MediaController) = c.playbackState?.state == PlaybackState.STATE_PLAYING

    private fun onMedia(c: MediaController) {
        val st = c.playbackState?.state ?: return
        val md = c.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val sig = "$st|$title"
        if (lastSig[c.sessionToken] == sig) return
        lastSig[c.sessionToken] = sig
        if (st != PlaybackState.STATE_PLAYING || title == null) return
        if (!Prefs.on(this, "media") || (currentKey == "media" && expanded)) return
        mediaRun?.let { h.removeCallbacks(it) }
        val r = Runnable { showMedia(c) }
        mediaRun = r
        h.postDelayed(r, 350)
    }

    private fun mediaCard(c: MediaController): Card? {
        val md = c.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val art: Drawable? = bmp?.let { BitmapDrawable(resources, it) }
        return Card("media", "NOW PLAYING", title, artist, art, c.sessionActivity, mc = c)
    }

    private fun showMedia(c: MediaController): Boolean {
        val card = mediaCard(c) ?: return false
        present(card, false)
        return true
    }

    private fun liveCard(): Card? {
        stickies.values.lastOrNull()?.let { return it }
        if (!Prefs.on(this, "media")) return null
        val c = ctrls.values.map { it.first }.firstOrNull { isPlaying(it) } ?: return null
        return mediaCard(c)
    }

    fun testMusicSafe(): Boolean = testMedia()

    fun testMedia(): Boolean {
        val all = ctrls.values.map { it.first }
        val c = all.firstOrNull { isPlaying(it) } ?: all.firstOrNull() ?: return false
        return showMedia(c)
    }

    fun mediaInfo(): String = if (ctrls.isEmpty()) "none seen yet" else ctrls.values.joinToString { it.first.packageName }

    // ---------- UI ----------
    fun test(exp: Boolean = false) {
        val c = Card("test", "TEST", "NOTHING ISLAND", "Tap to open. Hold to expand. Swipe up to close.", null, null,
            more = "Tap to open. Hold to expand. Swipe up to close. This is how a long message looks when expanded, with several lines of text.")
        if (exp) present(c, true) else enqueue(c)
    }

    private fun tv(t: String, sz: Float, color: Int, lines: Int) = TextView(this).apply {
        text = t; typeface = Prefs.font(this@IslandListener); setTextColor(color); textSize = sz
        maxLines = lines; ellipsize = TextUtils.TruncateAt.END
    }

    private fun restSize(): Pair<Float, Float> {
        val live = liveCard() != null
        return Pair(dp(Prefs.int(this, if (live) "liveW" else "idleW")).toFloat(),
            dp(Prefs.int(this, if (live) "liveH" else "idleH")).toFloat())
    }

    private fun present(card: Card, exp: Boolean) {
        if (!Settings.canDrawOverlays(this)) return
        val (sw, sh) = restSize()
        removeIdle(); removeNow()
        currentKey = card.key; expanded = exp
        val accent = Prefs.accent(this); val fg = Prefs.fg(this)
        val dim = Prefs.dim(this); val off = Prefs.off(this)
        val k = Prefs.int(this, "textScale") / 100f
        val isz = dp(Prefs.int(this, "iconSize"))
        if (card.tintIcon) card.icon?.mutate()?.setTint(fg)
        if (!exp) tick()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(Prefs.int(this@IslandListener, "bannerTop")), dp(18), dp(14))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        card.icon?.let { ic ->
            row.addView(ImageView(this).apply {
                setImageDrawable(ic); scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { setColor(Prefs.surface(this@IslandListener)); cornerRadius = isz * 0.28f }
                clipToOutline = true
                if (card.tintIcon) setPadding(isz / 6, isz / 6, isz / 6, isz / 6)
            }, LinearLayout.LayoutParams(isz, isz).apply { marginEnd = dp(12) })
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (card.tag.isNotEmpty()) col.addView(tv(card.tag, 10f * k, accent, 1))
        col.addView(tv(card.title.uppercase(), 13f * k, fg, 1))
        val body = if (exp && card.more.isNotEmpty()) card.more else card.text
        if (body.isNotEmpty()) col.addView(tv(body, 12f * k, dim, if (exp) 8 else 1))
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (card.mc != null) {
            row.addView(DotEq(this, accent), LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(10) })
        } else {
            row.addView(View(this).apply { background = circle(accent) },
                LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginStart = dp(10) })
        }
        content.addView(row)

        if (card.battery >= 0) {
            val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            for (i in 0 until 20) bar.addView(View(this).apply {
                background = circle(if (i < card.battery / 5) accent else off)
            }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(3) })
            content.addView(bar, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        }

        fun action(parent: LinearLayout, label: String, onClick: (TextView) -> Unit) = parent.addView(
            tv(label, 13f * k, fg, 1).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(4))
                setOnClickListener { onClick(this) }
            }, LinearLayout.LayoutParams(0, -2, 1f))

        val mc = card.mc
        val plain = card.key == "charge" || card.key == "ringer"
        if (exp && mc != null) {
            val dur = mc.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            if (dur > 0) {
                val dots = ArrayList<View>()
                val prog = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                for (i in 0 until 24) {
                    val d = View(this)
                    dots.add(d)
                    prog.addView(d, LinearLayout.LayoutParams(dp(5), dp(5)).apply { marginEnd = dp(3) })
                }
                content.addView(prog, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
                val r = object : Runnable {
                    override fun run() {
                        val ps = mc.playbackState ?: return
                        var pos = ps.position
                        if (ps.state == PlaybackState.STATE_PLAYING)
                            pos += ((SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong()
                        val lit = ((pos.toFloat() / dur) * 24).toInt().coerceIn(0, 24)
                        for (i in 0 until 24) dots[i].background = circle(if (i < lit) accent else off)
                        h.postDelayed(this, 1000)
                    }
                }
                tickRun = r
                r.run()
            }
            val ctl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            action(ctl, "<<") { mc.transportControls.skipToPrevious() }
            var playing = isPlaying(mc)
            action(ctl, if (playing) "PAUSE" else "PLAY") {
                if (playing) mc.transportControls.pause() else mc.transportControls.play()
                playing = !playing; it.text = if (playing) "PAUSE" else "PLAY"
            }
            action(ctl, ">>") { mc.transportControls.skipToNext() }
            content.addView(ctl)
        } else if (exp && !plain) {
            val ctl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            action(ctl, "OPEN") { try { card.pi?.send() } catch (e: Exception) {}; if (!card.sticky) dismiss() }
            action(ctl, "CLOSE") { stickies.remove(card.key); dismiss() }
            content.addView(ctl, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }

        // solid black shape (scales out of the pill) + content that fades in on top of it
        val bgView = View(this).apply { background = pillBg(Prefs.int(this@IslandListener, "radius")) }
        val box = FrameLayout(this)
        box.addView(bgView, FrameLayout.LayoutParams(-1, -1))
        box.addView(content, FrameLayout.LayoutParams(-1, -2))
        attachGestures(box, card)

        content.alpha = 0f
        bgView.scaleX = 0.01f; bgView.scaleY = 0.01f
        wm().addView(box, params(dp(Prefs.int(this, "width")), WindowManager.LayoutParams.WRAP_CONTENT, true,
            Prefs.int(this, "bannerX"), Prefs.int(this, "bannerY")))
        view = box; viewBg = bgView; viewContent = content
        box.post {
            bgView.pivotX = box.width / 2f; bgView.pivotY = 0f
            bgView.scaleX = sw / box.width.coerceAtLeast(1)
            bgView.scaleY = sh / box.height.coerceAtLeast(1)
            bgView.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(0.8f)).start()
            content.animate().alpha(1f).setStartDelay(110).setDuration(220).start()
        }
        h.postDelayed(dismissRun, if (exp) 8000L else Prefs.int(this, "secs") * 1000L)
    }

    private fun attachGestures(v: View, card: Card) {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                try { card.pi?.send() } catch (ex: Exception) {}
                if (view != null && !card.sticky) dismiss()
                return true
            }
            override fun onLongPress(e: MotionEvent) { tick(); h.post { present(card, true) } }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val m = card.mc
                if (m != null && abs(vx) > abs(vy) && abs(vx) > 800f) {
                    if (vx < 0) m.transportControls.skipToNext() else m.transportControls.skipToPrevious()
                    tick(); return true
                }
                if (vy < -400f) {
                    stickies.remove(card.key)
                    if (view != null) dismiss() else refreshIdle()
                    return true
                }
                return false
            }
        })
        v.setOnTouchListener { _, ev -> gd.onTouchEvent(ev) }
    }

    private fun stopTick() { tickRun?.let { h.removeCallbacks(it) }; tickRun = null }

    private fun removeNow() {
        h.removeCallbacks(dismissRun); stopTick()
        view?.let { try { wm().removeView(it) } catch (e: Exception) {} }
        view = null; viewBg = null; viewContent = null; currentKey = ""; expanded = false
    }

    private fun dismiss() {
        h.removeCallbacks(dismissRun); stopTick()
        val v = view; val bg = viewBg; val ct = viewContent
        view = null; viewBg = null; viewContent = null; currentKey = ""; expanded = false
        if (v == null || bg == null) {
            v?.let { try { wm().removeView(it) } catch (e: Exception) {} }
            restore(); return
        }
        val (tw, th) = restSize()
        val keepSolid = Prefs.on(this, "idle")
        ct?.animate()?.alpha(0f)?.setDuration(110)?.start()
        bg.animate().scaleX(tw / v.width.coerceAtLeast(1)).scaleY(th / v.height.coerceAtLeast(1))
            .alpha(if (keepSolid) 1f else 0f).setDuration(260).withEndAction {
                try { wm().removeView(v) } catch (e: Exception) {}
                if (view == null) restore()
            }.start()
    }

    private fun restore() {
        val next = pending.removeFirstOrNull()
        if (next != null) present(next, false) else showIdle()
    }

    // ---------- always-on pill ----------
    /** rebuild the pill (debounced so sliders stay smooth) */
    fun refreshIdle() {
        refreshRun?.let { h.removeCallbacks(it) }
        val r = Runnable { removeIdle(); if (view == null) showIdle() }
        refreshRun = r
        h.postDelayed(r, 60)
    }

    /** just move / resize the pill, no rebuild */
    fun moveIdle() {
        h.post {
            val v = idle ?: return@post
            val w = dp(Prefs.int(this, if (idleLive) "liveW" else "idleW"))
            val hg = dp(Prefs.int(this, if (idleLive) "liveH" else "idleH"))
            try { wm().updateViewLayout(v, params(w, hg, idleLive)) } catch (e: Exception) {}
        }
    }

    private fun removeIdle() {
        idleAnim?.cancel(); idleAnim = null
        idle?.let { try { wm().removeView(it) } catch (e: Exception) {} }
        idle = null
    }

    private fun showIdle() {
        if (idle != null || view != null) return
        if (!Prefs.on(this, "idle") || !Settings.canDrawOverlays(this)) return
        val accent = Prefs.accent(this)
        val live = liveCard()
        idleLive = live != null
        val hgt = dp(Prefs.int(this, if (live != null) "liveH" else "idleH"))
        val isz = hgt - dp(10)
        val pill = FrameLayout(this).apply { background = pillBg(60) }
        if (live != null) {
            if (live.tintIcon) live.icon?.mutate()?.setTint(Prefs.fg(this))
            live.icon?.let { ic ->
                pill.addView(ImageView(this).apply {
                    setImageDrawable(ic); scaleType = ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable().apply { setColor(Prefs.surface(this@IslandListener)); cornerRadius = isz * 0.28f }
                    clipToOutline = true
                    if (live.tintIcon) setPadding(isz / 8, isz / 8, isz / 8, isz / 8)
                }, FrameLayout.LayoutParams(isz, isz, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(9) })
            }
            if (live.mc != null) {
                pill.addView(DotEq(this, accent), FrameLayout.LayoutParams(isz, isz, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(10) })
            } else {
                pill.addView(tv(live.tag, 9f, accent, 1),
                    FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(12) })
            }
            attachGestures(pill, live)
        } else if (Prefs.flag(this, "dot", true)) {
            val dot = View(this).apply { background = circle(accent) }
            pill.addView(dot, FrameLayout.LayoutParams(dp(6), dp(6), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(10) })
            idleAnim = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.25f).apply {
                duration = 1400; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
            }
        }
        val w = dp(Prefs.int(this, if (live != null) "liveW" else "idleW"))
        try {
            wm().addView(pill, params(w, hgt, live != null))
            idle = pill
        } catch (e: Exception) { idleAnim?.cancel(); idleAnim = null }
    }
}
