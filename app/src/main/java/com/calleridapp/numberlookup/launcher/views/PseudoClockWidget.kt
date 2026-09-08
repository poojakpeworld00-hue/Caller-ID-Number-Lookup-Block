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
 * Root of [R.layout.pseudo_widget_digital_clock]: three plain TextViews showing the weekday,
 * the current time and the date. The system ticks us once a minute while the home screen is up,
 * which is all the resolution the clock needs, so there is no timer of our own to leak.
 */
class PseudoClockWidget @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private var weekday: TextView? = null
    private var time: TextView? = null
    private var date: TextView? = null
    private var isReceiverRegistered = false

    private val ticker = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        weekday = findViewById(R.id.widget_weekday)
        time = findViewById(R.id.widget_text_clock)
        date = findViewById(R.id.widget_date)
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

        // The date is the one line whose field order is not ours to choose — "8 September" here
        // is "September 8" elsewhere — so the pattern is asked for rather than hardcoded.
        val datePattern = DateFormat.getBestDateTimePattern(locale, DAY_AND_MONTH)

        weekday?.text = SimpleDateFormat(WEEKDAY, locale).format(now)
        time?.text = SimpleDateFormat(timePattern, locale).format(now)
        date?.text = SimpleDateFormat(datePattern, locale).format(now)
    }

    companion object {
        private const val HOUR_24 = "HH:mm"
        private const val HOUR_12 = "h:mm"
        private const val WEEKDAY = "EEEE"
        private const val DAY_AND_MONTH = "dMMMM"
    }
}
