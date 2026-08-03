package com.calleridapp.numberlookup.ui.common

import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.data.CallKind
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Formatting helpers shared by call-log adapters. */
object CallPresenter {

    fun displayName(name: String?, number: String): String =
        if (!name.isNullOrBlank()) name else number

    fun initials(name: String?, number: String): String {
        if (!name.isNullOrBlank()) {
            val parts = name.trim().split(" ").filter { it.isNotEmpty() }
            val letters = parts.take(2).joinToString("") { it.first().uppercase() }
            if (letters.isNotEmpty()) return letters
        }
        return "#"
    }

    fun timeLabel(date: Long): String {
        val pattern = if (isToday(date)) "h:mm a" else "MMM d"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(date))
    }

    fun durationLabel(seconds: Long): String {
        if (seconds <= 0) return ""
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    fun typeLabelRes(type: CallKind): Int = when (type) {
        CallKind.INCOMING -> R.string.type_incoming
        CallKind.OUTGOING -> R.string.type_outgoing
        CallKind.MISSED -> R.string.type_missed
        CallKind.SPAM -> R.string.type_blocked
    }

    fun typeIconRes(type: CallKind): Int = when (type) {
        CallKind.INCOMING -> R.drawable.ic_call_received
        CallKind.OUTGOING -> R.drawable.ic_call_made
        CallKind.MISSED -> R.drawable.ic_call_missed
        CallKind.SPAM -> R.drawable.ic_warning
    }

    fun typeColorRes(type: CallKind): Int = when (type) {
        CallKind.INCOMING, CallKind.OUTGOING -> R.color.on_surface_variant
        CallKind.MISSED -> R.color.danger
        CallKind.SPAM -> R.color.warn
    }

    private fun isToday(date: Long): Boolean {
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_YEAR)
        val year = cal.get(Calendar.YEAR)
        cal.timeInMillis = date
        return cal.get(Calendar.DAY_OF_YEAR) == today && cal.get(Calendar.YEAR) == year
    }
}
