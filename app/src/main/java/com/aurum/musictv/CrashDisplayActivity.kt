package com.aurum.musictv

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * TEMPORARY diagnostic activity — see AurumTvApp's uncaught exception
 * handler. Shows a crash's full stack trace as plain scrollable text so
 * it can be screenshotted without adb/logcat access. Remove both this
 * file and the handler in AurumTvApp once the app is stable.
 */
class CrashDisplayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val trace = intent.getStringExtra("stack_trace") ?: "No stack trace available."

        val textView = TextView(this).apply {
            text = "App crashed — copy/screenshot this:\n\n$trace"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.BLACK)
            textSize = 12f
            setPadding(32, 32, 32, 32)
            setTextIsSelectable(true)
        }

        val scrollView = ScrollView(this).apply {
            addView(textView)
        }

        setContentView(scrollView)
    }
}
