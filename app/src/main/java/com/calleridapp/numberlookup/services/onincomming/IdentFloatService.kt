package com.calleridapp.numberlookup.services.onincomming

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.services.CallEndSentinel
import com.calleridapp.numberlookup.services.IdentCard
import com.calleridapp.numberlookup.ui.incall.InboundCallActivity
import com.calleridapp.numberlookup.util.IdentIdRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows the caller-ID card while a call is ringing.
 *
 * - **Device unlocked** → a floating [WindowManager] overlay (TYPE_APPLICATION_OVERLAY),
 *   which is why the app requires SYSTEM_ALERT_WINDOW.
 * - **Device locked** → overlays are unreliable over the keyguard, so we hand off to
 *   [InboundCallActivity] (showWhenLocked + turnScreenOn) and stop.
 *
 * Started by [CallStateReceiver] on RINGING and stopped on OFFHOOK/IDLE.
 */
class IdentFloatService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    /** Self-dismiss the moment the call leaves the ringing/active state. */
    private val callEndWatcher by lazy { CallEndSentinel(this) { stopSelf() } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val number = intent?.getStringExtra(EXTRA_NUMBER)?.takeIf { it.isNotBlank() } ?: run {
            stopSelf(); return START_NOT_STICKY
        }

        val canOverlay = Settings.canDrawOverlays(this)
        val keyguard = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        val locked = keyguard?.isKeyguardLocked == true

        // The floating card is only possible with SYSTEM_ALERT_WINDOW, and the app no
        // longer asks for it (see FloatKit.ASK_FOR_OVERLAY). So the full-screen activity
        // is now the main route, not just the locked-screen one:
        //  - locked            → activity, the only thing that shows over the keyguard;
        //  - no overlay        → activity, started on the default-role background-start
        //                        exemption (home / dialer / call screening);
        //  - overlay + awake   → the floating card, unchanged.
        if (locked || !canOverlay) {
            if (!canOverlay && !IdentIdRegistry.holdsSystemDefaultRole(this)) {
                // Nothing to start from: no overlay window and no role to start an
                // activity with. The system's own incoming-call UI is all the user gets.
                Log.w(TAG, "no overlay permission and no default role — no caller-ID card")
                stopSelf(); return START_NOT_STICKY
            }
            runCatching { startActivity(InboundCallActivity.newIntent(this, number)) }
                .onFailure { Log.w(TAG, "caller-ID activity start refused", it) }
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        showOverlay(number)
        callEndWatcher.start()
        return START_STICKY
    }

    private fun showOverlay(number: String) {
        // Replace any previous card (e.g. rapid re-start) before adding a new one.
        removeOverlay()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_caller_id, null)
        view.findViewById<View>(R.id.btnIncallClose).setOnClickListener { stopSelf() }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            val density = resources.displayMetrics.density
            // Inset from the screen edges so the card doesn't span full width.
            width = resources.displayMetrics.widthPixels - (24 * density).toInt()
        }

        runCatching {
            windowManager?.addView(view, params)
            overlayView = view
        }.onFailure {
            Log.w(TAG, "addView failed", it)
            stopSelf()
            return
        }

        // Resolve caller details off the main thread, then bind.
        scope.launch {
            val info = withContext(Dispatchers.IO) { IdentCard.resolve(this@IdentFloatService, number) }
            overlayView?.let { IdentCard.bind(this@IdentFloatService, it, number, info) }
        }
    }

    private fun removeOverlay() {
        overlayView?.let { v -> runCatching { windowManager?.removeView(v) } }
        overlayView = null
    }

    /**
     * Keep the service alive during the ring. startForeground can be refused when the
     * PHONE_STATE broadcast's exemption has elapsed — that's fine, the WindowManager
     * overlay does not depend on the foreground service, so we swallow it.
     */
    private fun startAsForeground() {
        val channelId = "caller_id_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(
                NotificationChannel(channelId, getString(R.string.incall_service_active), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.incall_service_active))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notification)
            }
        }.onFailure { Log.w(TAG, "startForeground refused — running as plain service", it) }
    }

    override fun onDestroy() {
        callEndWatcher.stop()
        removeOverlay()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CallerOverlay"
        const val EXTRA_NUMBER = "extra_number"

        /**
         * How long a start for one number suppresses a second start for the same number.
         *
         * The card has two triggers: [ScreenerService.onScreenCall], which fires before the
         * phone rings whenever we hold the CallScreening role, and [CallStateReceiver]'s
         * RINGING broadcast, which is the only trigger without the role. When we do hold it
         * both fire for the same call, milliseconds apart — this window swallows the second.
         *
         * It has to be a plain timestamp rather than an "is the service running" flag: on a
         * locked device the service hands off to [InboundCallActivity] and immediately stops
         * itself, so by the time the broadcast lands there is no service left to check and
         * the hand-off would happen twice.
         */
        private const val DEDUPE_WINDOW_MS = 5_000L

        private var lastStartedNumber: String? = null
        private var lastStartedAt = 0L

        fun start(context: Context, number: String) {
            if (isDuplicateStart(number)) {
                Log.d(TAG, "card already raised for $number — duplicate start ignored")
                return
            }
            lastStartedNumber = number
            lastStartedAt = System.currentTimeMillis()

            val intent = Intent(context, IdentFloatService::class.java)
                .putExtra(EXTRA_NUMBER, number)
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "startService refused — no card this call", it) }
        }

        fun stop(context: Context) {
            // The call is over, so the next start for this number is a new call, not a duplicate.
            lastStartedNumber = null
            lastStartedAt = 0L
            runCatching { context.stopService(Intent(context, IdentFloatService::class.java)) }
        }

        private fun isDuplicateStart(number: String): Boolean {
            val previous = lastStartedNumber ?: return false
            if (System.currentTimeMillis() - lastStartedAt > DEDUPE_WINDOW_MS) return false
            return sameNumber(previous, number)
        }

        /**
         * Compares on the last 10 digits: the screening service reports the raw SIP/tel
         * handle ("+917016414568") while the broadcast can carry a locally formatted one,
         * and a strict equals would let the duplicate through.
         */
        private fun sameNumber(a: String, b: String): Boolean {
            val x = a.filter(Char::isDigit).takeLast(10)
            return x.isNotEmpty() && x == b.filter(Char::isDigit).takeLast(10)
        }
    }
}
