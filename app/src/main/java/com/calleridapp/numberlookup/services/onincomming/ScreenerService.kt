package com.calleridapp.numberlookup.services.onincomming

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import com.calleridapp.numberlookup.data.BlockRosterRegistry

/**
 * Active only while the app holds the CallScreening role (Android 10+, the
 * "Caller ID & spam app" default). It drives two things for every incoming call:
 *
 *  - **Blocking** — silently rejects blocked numbers **before** they ring. Unlike the
 *    PHONE_STATE receiver's endCall() fallback, the call never rings through.
 *  - **Caller-ID card** — for calls we let through, raises [IdentFloatService] here
 *    rather than waiting for the RINGING broadcast. This is the better trigger: it
 *    fires before the phone rings, the number comes from [Call.Details] so it needs no
 *    READ_PHONE_STATE, and holding the role is itself the background-start exemption
 *    the card needs.
 *
 * [CallStateReceiver] still raises the card on RINGING — that is the only path on
 * pre-Android-10 devices and whenever another app holds the role. The two overlap
 * whenever we *do* hold it, so [IdentFloatService.start] dedupes them.
 *
 * Note the card is only *raised* here; there is no call-end callback on a screening
 * service (the system unbinds right after [respondToCall]), so dismissal stays with
 * [CallStateReceiver] and [com.calleridapp.numberlookup.services.CallEndSentinel].
 */
class ScreenerService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val isIncoming = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            callDetails.callDirection == Call.Details.DIRECTION_INCOMING
        } else true

        val number = callDetails.handle?.schemeSpecificPart // tel: number
        val block = isIncoming && !number.isNullOrBlank() &&
                BlockRosterRegistry(this).isBlocked(number)

        if (block) Log.d(TAG, "blocked incoming call screened: $number")

        val response = CallResponse.Builder()
            .setDisallowCall(block)   // don't let the call through
            .setRejectCall(block)     // hang up immediately
            .setSkipCallLog(false)    // still record it in the call log
            .setSkipNotification(block) // no missed-call notification for blocked
            .build()

        // Respond first: the system only waits a few seconds before it gives up on us,
        // lets the call through and unbinds. Everything else happens after.
        respondToCall(callDetails, response)

        if (isIncoming && !block && !number.isNullOrBlank()) {
            Log.d(TAG, "raising caller-ID card from screening: $number")
            IdentFloatService.start(this, number)
        }
    }

    companion object {
        private const val TAG = "CallScreening"
    }
}
