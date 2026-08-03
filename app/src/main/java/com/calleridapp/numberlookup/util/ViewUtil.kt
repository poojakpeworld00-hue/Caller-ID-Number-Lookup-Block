package com.calleridapp.numberlookup.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.calleridapp.admesh.presentation.oninterAds.InterstitialNormal

inline fun <reified T : Activity> Context.openActivity(
    clearTop: Boolean = false,
    isShowAd: Boolean = true,
    extras: Bundle? = null
) {
    val intent = Intent(this, T::class.java)
    extras?.let { intent.putExtras(it) }
    if (clearTop) {
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    val activity = this as? Activity
    if (isShowAd && activity != null) {
        try {
            InterstitialNormal().showInterAds(activity) {
                activity.startActivity(intent)
            }
        } catch (_: Exception) {
            startActivity(intent)
        }
    } else {
        startActivity(intent)
    }
}

fun Context.openActivity(intent: Intent, isShowAd: Boolean = true) {
    val activity = this as? Activity
    if (isShowAd && activity != null) {
        try {
            InterstitialNormal().showInterAds(activity) {
                activity.startActivity(intent)
            }
        } catch (_: Exception) {
            startActivity(intent)
        }
    } else {
        startActivity(intent)
    }
}

/**
 * Configure-lambda overload — drop-in replacement for the old `launch<T> {}`
 * helper. Build the Intent inline, then route through the standard
 * interstitial-aware launcher.
 */
inline fun <reified T : Activity> Context.openActivity(
    clearTop: Boolean = false,
    isShowAd: Boolean = true,
    crossinline configure: Intent.() -> Unit
) {
    val intent = Intent(this, T::class.java).apply(configure)
    if (clearTop) {
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    openActivity(intent, isShowAd)
}

fun View.triggerClick(onClick: (View?) -> Unit) {
    setOnClickListener { view ->
        // Haptic feedback
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

        // Click animation: shrink and restore
        view.animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(100)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()

                // Call your click listener after animation
                onClick.invoke(view)
            }.start()
    }
}

fun EditText.onDone(callback: () -> Unit) {
    this.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_DONE) {
            callback()
            true
        } else {
            false
        }
    }
}
