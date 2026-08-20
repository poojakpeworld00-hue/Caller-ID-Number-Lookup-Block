package com.calleridapp.numberlookup.services.onincomming

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.presentation.AppOpenAdRegistry
import com.calleridapp.admesh.presentation.my_main_counter.FloatingViewRegistry
import com.calleridapp.admesh.presentation.my_main_counter.My_Shell_Screen
import com.calleridapp.admesh.presentation.my_main_counter.service.ShelllJobService.Companion.NOTIFICATION_ID
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.data.BlockRosterRegistry
import com.calleridapp.numberlookup.launcher.extensions.isDefaultLauncher
import com.calleridapp.numberlookup.ui.incall.InboundCallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date

/**
 * THE single [TelephonyManager.ACTION_PHONE_STATE_CHANGED] receiver for the app.
 * Consolidates what used to be two duplicate receivers. It drives both:
 *
 *  - **Caller-ID card** — RINGING (+ number + overlay) → show [IdentFloatService];
 *    OFFHOOK / IDLE → dismiss it (stop the service, finish any [InboundCallActivity]).
 *    Raising the card is shared with [ScreenerService], which gets there first whenever
 *    we hold the CallScreening role; dismissing it is this receiver's alone, because a
 *    screening service gets no call-end callback.
 *  - **Post-call summary** — on IDLE, determine the call type and show
 *    [My_Shell_Screen] (overlay/FGS path) or a full-screen notification fallback.
 *
 * Registered in the manifest (fires when the app is dead) and also dynamically by
 * ShelllJobService while the app is alive. A 2-second debounce on IDLE dedupes the
 * overlapping registrations.
 */
class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        if (!number.isNullOrEmpty()) lastNumber = number
        Log.d(TAG, "state=$state number=$number")

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                wasRinging = true
                wasOffhook = false
                callStartTime = System.currentTimeMillis()

                // Reject blocked numbers immediately — no ring-through, no caller-ID card.
                if (!number.isNullOrBlank() && BlockRosterRegistry(context).isBlocked(number)) {
                    wasBlocked = true
                    Log.d(TAG, "blocked number rejected: $number")
                    endCall(context)
                    return
                }

                // Caller-ID card needs a number AND the overlay permission.
                // When we hold the CallScreening role, [ScreenerService] has already raised
                // the card before this broadcast arrived; IdentFloatService.start() drops
                // this call as a duplicate. This branch is what covers pre-Android-10 and
                // any device where another app owns the role.
                if (!number.isNullOrBlank() && Settings.canDrawOverlays(context)) {
                    IdentFloatService.start(context, number)
                } else if (number.isNullOrBlank()) {
                    Log.w(TAG, "ringing without a number — skipping caller-ID card")
                }
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                wasOffhook = true
                if (callStartTime == 0L) callStartTime = System.currentTimeMillis() // outgoing
                dismissCard(context)
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                dismissCard(context)

                // A blocked call was rejected — don't show the post-call summary.
                if (wasBlocked) {
                    resetState()
                    return
                }

                // Debounce duplicate IDLE broadcasts (manifest + dynamic registration).
                val now = System.currentTimeMillis()
                if (now - lastTime < 2000) {
                    Log.d(TAG, "duplicate IDLE skipped")
                    resetState()
                    return
                }
                lastTime = now

                val phoneNumber = lastNumber ?: "Private Number"
                val endTime = Date()
                val startTime = if (callStartTime > 0) Date(callStartTime) else endTime
                val callType = when {
                    wasRinging && !wasOffhook -> "MISSED"    // rang, never answered
                    wasRinging && wasOffhook -> "INCOMING"   // rang and answered
                    !wasRinging && wasOffhook -> "OUTGOING"  // dialed out
                    else -> "UNKNOWN"
                }

                handlePostCall(context, phoneNumber, startTime, endTime, callType)
                resetState()
            }
        }
    }

    /** Tears down the caller-ID card and tells any locked-screen activity to finish. */
    private fun dismissCard(context: Context) {
        IdentFloatService.stop(context)
        context.sendBroadcast(Intent(ACTION_CALL_ENDED).setPackage(context.packageName))
    }

    private fun resetState() {
        callStartTime = 0L
        lastNumber = null
        wasRinging = false
        wasOffhook = false
        wasBlocked = false
    }

    /**
     * Best-effort rejection of a blocked call via the hidden ITelephony.endCall()
     * (reflection — needs no runtime permission).
     *
     * This is only the fallback for devices that don't hold the CallScreening role;
     * the role-based [ScreenerService] is the primary blocker and rejects
     * calls before they ring. Note Android 9+ (API 28+) restricts this private API,
     * so it may be a no-op there — which is why granting the CallScreening role is
     * the reliable path.
     */
    @Suppress("DiscouragedPrivateApi", "PrivateApi")
    private fun endCall(context: Context) {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val method = tm.javaClass.getDeclaredMethod("getITelephony").apply { isAccessible = true }
            val telephony = method.invoke(tm) ?: return
            telephony.javaClass.getDeclaredMethod("endCall").invoke(telephony)
        } catch (e: Exception) {
            Log.e(TAG, "endCall failed", e)
        }
    }

    // -------------------- Post-call summary screen --------------------

    /**
     * Routes the post-call screen to whichever path this device actually allows.
     *
     * 1. **Overlay granted** — the reliable route. On 14+ the invisible overlay window in
     *    [FloatingViewRegistry] is what buys the background-activity-start; below that a
     *    plain start is enough.
     * 2. **No overlay, but we hold a system default role** (home / dialer / call screening)
     *    — that role is itself a background-activity-start exemption, so the screen is
     *    started directly. Without this branch the whole case fell to the notification,
     *    which posted nothing on an awake screen: after a call the user saw *nothing at all*
     *    unless they had granted "display over other apps".
     * 3. **Neither** — the full-screen-intent notification.
     *
     * A blocked background start neither throws nor reports anything (the system just drops
     * it with a log line), so path 2 is confirmed rather than trusted: after
     * [CALLBACK_CONFIRM_MS] the screen has either marked itself active or it never arrived,
     * and the notification takes over. [My_Shell_Screen] cancels our notifications when it
     * does come up, so the two can never both stand.
     */
    private fun handlePostCall(
        context: Context, phoneNumber: String, startTime: Date, endTime: Date, type: String
    ) {
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                if (!AdsVault.getInstance(context).getBoolean("HD_VBC_Show")) return@launch

                AppOpenAdRegistry.callbackshow = true
                delay(500)

                val hasOverlay = canShowOverlay(context)
                val started = when {
                    hasOverlay && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                        runCatching {
                            FloatingViewRegistry(context)
                                .showCallbackScreen(phoneNumber, startTime, endTime, type)
                        }.isSuccess

                    hasOverlay || holdsSystemDefaultRole(context) ->
                        runCatching {
                            launchCallbackScreen(context, phoneNumber, startTime, endTime, type)
                        }.isSuccess

                    else -> false
                }

                if (!started) {
                    showFullScreenNotification(context, phoneNumber, startTime, endTime, type)
                    return@launch
                }

                // Only the overlay paths are trustworthy; a role-backed start has to prove it.
                if (!hasOverlay) {
                    delay(CALLBACK_CONFIRM_MS)
                    if (!My_Shell_Screen.isActive) {
                        Log.w(TAG, "role-backed callback start did not surface — notifying")
                        showFullScreenNotification(context, phoneNumber, startTime, endTime, type)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun canShowOverlay(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /**
     * True when this app currently holds a default system role. Each of these makes the app
     * the user's explicit choice for something, and each carries a background-activity-start
     * exemption — which is what the post-call screen needs when there is no overlay
     * permission to lean on.
     *
     * ROLE_HOME is checked through [isDefaultLauncher] because it also has to answer on
     * API 26-28, where RoleManager does not exist.
     */
    private fun holdsSystemDefaultRole(context: Context): Boolean {
        if (runCatching { context.isDefaultLauncher() }.getOrDefault(false)) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return runCatching {
            listOf(RoleManager.ROLE_DIALER, RoleManager.ROLE_CALL_SCREENING).any {
                rm.isRoleAvailable(it) && rm.isRoleHeld(it)
            }
        }.getOrDefault(false)
    }

    private fun launchCallbackScreen(
        context: Context, phone: String, start: Date, end: Date, type: String
    ) {
        try {
            val intent = Intent(context, My_Shell_Screen::class.java).apply {
                putExtra("phone", phone)
                putExtra("start_time", start.time)
                putExtra("end_time", end.time)
                putExtra("call_type", type)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "callback activity launch failed", e)
        }
    }

    private fun showFullScreenNotification(
        context: Context, phone: String, start: Date, end: Date, type: String
    ) {
        Log.e(TAG, "showFullScreenNotification: ")
        if (My_Shell_Screen.isActive || InboundCallActivity.isActive) {
            Log.d(TAG, "post-call screen in foreground — suppressing notification")
            return
        }
        // Deliberately NOT gated on a locked or sleeping screen any more. It used to be, and
        // that is what made the post-call screen invisible without the overlay permission: on
        // an awake phone — which is exactly where a user is right after hanging up — the
        // fallback simply posted nothing. On a locked screen the full-screen intent still
        // takes over the display; on an awake one it lands as a heads-up the user can tap.
        val channelId = "post_call_channel"
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Post Call Info", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows callback screen after a call"
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(context, My_Shell_Screen::class.java).apply {
            putExtra("phone", phone)
            putExtra("start_time", start.time)
            putExtra("end_time", end.time)
            putExtra("call_type", type)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.post_call_notif_title, phone))
            .setContentText(context.getString(R.string.post_call_notif_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).cancelAll()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "CallStateReceiver"

        /**
         * How long to wait before deciding a role-backed activity start was dropped. Long
         * enough for the screen to reach `onResume` on a slow device, short enough that the
         * notification fallback still feels like part of hanging up. Well inside the ~10s
         * a `goAsync` receiver is allowed.
         */
        private const val CALLBACK_CONFIRM_MS = 1_500L
        const val ACTION_CALL_ENDED = "com.calleridapp.numberlookup.CALL_ENDED"

        // Cross-broadcast call-state tracking (receiver instances are short-lived).
        private var lastTime = 0L
        private var callStartTime = 0L
        private var lastNumber: String? = null
        private var wasRinging = false
        private var wasOffhook = false
        private var wasBlocked = false
    }
}
