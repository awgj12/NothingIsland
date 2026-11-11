package com.example.nothingisland

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
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
import androidx.core.content.res.ResourcesCompat

class IslandListener : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var island: View? = null
    private val autoCollapse = Runnable { collapse() }

    companion object { var instance: IslandListener? = null }

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { instance = null; collapse() }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (sbn.packageName == packageName) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val icon = try { packageManager.getApplicationIcon(sbn.packageName) } catch (e: Exception) { null }
        handler.post { show(title, text, icon, n.contentIntent) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun show(title: String, text: String, icon: Drawable?, pi: PendingIntent?) {
        if (!Settings.canDrawOverlays(this)) return
        collapse()
        val ctx: Context = this
        val fontId = resources.getIdentifier("dotfont", "font", packageName)
        val dot: Typeface = (if (fontId != 0) ResourcesCompat.getFont(ctx, fontId) else null) ?: Typeface.MONOSPACE

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.BLACK); cornerRadius = dp(40).toFloat()
                setStroke(dp(1), Color.parseColor("#2A2A2A"))
            }
            elevation = dp(8).toFloat()
        }
        root.addView(ImageView(ctx).apply { setImageDrawable(icon) },
            LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) })

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx).apply {
            this.text = title.uppercase(); typeface = dot; setTextColor(Color.WHITE)
            textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        if (text.isNotEmpty()) col.addView(TextView(ctx).apply {
            this.text = text; typeface = dot; setTextColor(Color.parseColor("#9A9A9A"))
            textSize = 12f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        root.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#D71921")) }
        }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginStart = dp(10) })

        root.setOnClickListener { try { pi?.send() } catch (e: Exception) {}; collapse() }

        val lp = WindowManager.LayoutParams(
            dp(340), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(10) }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        wm.addView(root, lp)
        island = root

        root.pivotY = 0f
        root.scaleX = 0.25f; root.scaleY = 0.5f; root.alpha = 0f
        root.post { root.pivotX = root.width / 2f }
        root.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(420)
            .setInterpolator(OvershootInterpolator(1.1f)).start()
        handler.postDelayed(autoCollapse, 4000)
    }

    private fun collapse() {
        handler.removeCallbacks(autoCollapse)
        val v = island ?: return
        island = null
        v.animate().scaleX(0.25f).scaleY(0.5f).alpha(0f).setDuration(220).withEndAction {
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) {}
        }.start()
    }
}
