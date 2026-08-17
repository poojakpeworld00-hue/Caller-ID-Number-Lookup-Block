package com.calleridapp.numberlookup.util

import android.util.Log
import com.calleridapp.numberlookup.BuildConfig
import com.google.firebase.crashlytics.FirebaseCrashlytics

object GuardRail {

    private const val TAG = "GuardRail"

    /** Logs only in Debug mode */
    fun log(tag: String = TAG, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
        }
    }

    /**
     * Logs an error (still safe in Release).
     *
     * In release the message goes to Crashlytics: as a breadcrumb always, and as a non-fatal
     * report when there is a [throwable]. Every call is guarded — this is the path crash handling
     * itself reports through ([CrashGuard]), so it must not be able to throw.
     */
    fun error(tag: String = TAG, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            Log.e(tag, message, throwable)
            return
        }

        runCatching {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.log("$tag: $message")
            if (throwable != null) crashlytics.recordException(throwable)
        }
    }

    /** Executes a block only in Debug builds */
    inline fun runDebug(block: () -> Unit) {
        if (BuildConfig.DEBUG) block()
    }
}
