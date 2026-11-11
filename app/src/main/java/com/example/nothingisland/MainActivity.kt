package com.example.nothingisland

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK); setPadding(pad, pad, pad, pad)
        }
        col.addView(TextView(this).apply {
            text = "NOTHING ISLAND"; setTextColor(Color.WHITE); textSize = 26f
            typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
        })
        fun btn(label: String, onClick: () -> Unit) = col.addView(Button(this).apply {
            text = label; setTextColor(Color.WHITE); typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#D71921")); setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad / 2 })

        btn("1. ALLOW DISPLAY OVER APPS") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("2. ALLOW NOTIFICATION ACCESS") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        btn("TEST ISLAND") {
            IslandListener.instance?.show("Nothing Island", "It works. Tap me.", null, null)
        }
        setContentView(col)
    }
}
