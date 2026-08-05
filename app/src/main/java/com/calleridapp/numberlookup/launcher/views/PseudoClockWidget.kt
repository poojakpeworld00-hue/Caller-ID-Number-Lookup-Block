package com.calleridapp.numberlookup.launcher.views

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.calleridapp.numberlookup.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Root of [R.layout.pseudo_widget_digital_clock]: two plain TextViews showing the current
 * time and weekday. The system ticks us once a minute while the home screen is up, which is
 * all the resolution the clock needs, so there is no timer of our own to leak.
 */
class PseudoClockWidget @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private var time: TextView? = null
    private var day: TextView? = null
    private var isReceiverRegistered = false

    private val ticker = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        time = findViewById(R.id.widget_text_clock)
        day = findViewById(R.id.widget_date)
        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }

        ContextCompat.registerReceiver(
            context, ticker, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isReceiverRegistered = true
        refresh()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (isReceiverRegistered) {
            isReceiverRegistered = false
            try {
                context.unregisterReceiver(ticker)
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    // the minute tick does not fire while the screen is off, so catch up on the way back
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            refresh()
        }
    }

    private fun refresh() {
        val now = Date()
        val locale = Locale.getDefault()
        val timePattern = if (DateFormat.is24HourFormat(context)) HOUR_24 else HOUR_12

        time?.text = SimpleDateFormat(timePattern, locale).format(now)
        day?.text = SimpleDateFormat(WEEKDAY, locale).format(now)
    }

    companion object {
        private const val HOUR_24 = "HH:mm"
        private const val HOUR_12 = "h:mm"
        private const val WEEKDAY = "EEEE"
    }
}
