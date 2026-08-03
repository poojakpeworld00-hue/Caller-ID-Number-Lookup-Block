package com.calleridapp.numberlookup.data

import android.content.Context
import android.provider.CallLog

/** A single entry from the system call log. */
data class CallRecord(
    val name: String?,
    val number: String,
    val type: CallKind,
    val date: Long,
    val durationSec: Long
)

/** A phone number aggregated by how often it appears in the call log. */
data class TopUsedDigit(
    val name: String?,
    val number: String,
    val count: Int
)

/** Reads the device call log via the [CallLog.Calls] content provider. */
class CallLogSource(private val context: Context) {

    fun getCalls(limit: Int = 500): List<CallRecord> {
        val result = mutableListOf<CallRecord>()
        val projection = arrayOf(
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )

        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
            val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
            val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
            val durationIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)

            while (cursor.moveToNext() && result.size < limit) {
                val type = when (cursor.getInt(typeIdx)) {
                    CallLog.Calls.INCOMING_TYPE -> CallKind.INCOMING
                    CallLog.Calls.OUTGOING_TYPE -> CallKind.OUTGOING
                    CallLog.Calls.MISSED_TYPE -> CallKind.MISSED
                    CallLog.Calls.REJECTED_TYPE -> CallKind.MISSED
                    CallLog.Calls.BLOCKED_TYPE -> CallKind.SPAM
                    else -> CallKind.INCOMING
                }
                result.add(
                    CallRecord(
                        name = cursor.getString(nameIdx),
                        number = cursor.getString(numberIdx).orEmpty().ifBlank { "Unknown" },
                        type = type,
                        date = cursor.getLong(dateIdx),
                        durationSec = cursor.getLong(durationIdx)
                    )
                )
            }
        }
        return result
    }

    /**
     * Returns the most frequently called numbers, busiest first.
     * Caller must ensure READ_CALL_LOG is granted (otherwise the list is empty).
     */
    fun getMostUsed(limit: Int = 20): List<TopUsedDigit> =
        getCalls(limit = 1000)
            .filter { it.number.isNotBlank() && !it.number.equals("Unknown", ignoreCase = true) }
            .groupBy { it.number }
            .map { (number, entries) ->
                TopUsedDigit(
                    name = entries.firstOrNull { !it.name.isNullOrBlank() }?.name,
                    number = number,
                    count = entries.size
                )
            }
            .sortedByDescending { it.count }
            .take(limit)
}
