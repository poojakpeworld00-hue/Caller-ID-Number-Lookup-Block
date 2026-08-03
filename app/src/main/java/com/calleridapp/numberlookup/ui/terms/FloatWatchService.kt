package com.calleridapp.numberlookup.ui.terms

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Polls for the "display over other apps" permission while the system Settings
 * screen is shown, then broadcasts [ACTION_OVERLAY_GRANTED] and stops itself.
 *
 * Replaces the in-activity coroutine watcher: the work outlives the activity's
 * resumed state (it keeps polling while we sit behind Settings) and is owned by
 * the system service lifecycle instead of a Job.
 */
class FloatWatchService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            if (FloatKit.isGranted(this@FloatWatchService)) {
                sendBroadcast(Intent(ACTION_OVERLAY_GRANTED).setPackage(packageName))
                stopSelf()
            } else {
                handler.postDelayed(this, POLL_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(poll)
        handler.post(poll)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        super.onDestroy()
    }

    companion object {
        const val ACTION_OVERLAY_GRANTED =
            "com.calleridapp.numberlookup.action.OVERLAY_GRANTED"
        private const val POLL_MS = 500L

        fun start(context: Context) {
            context.startService(Intent(context, FloatWatchService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatWatchService::class.java))
        }
    }
}
