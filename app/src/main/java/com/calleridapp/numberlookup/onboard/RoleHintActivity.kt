package com.calleridapp.numberlookup.onboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.util.GuardRail

/**
 * The "pick this app" hint drawn over the system *Default home app* list: a centred card on a
 * dimmed page, with a tapping hand and a pulsing radio on this app's row so the user can find it in
 * a list of look-alike launchers.
 *
 * A translucent activity in its own task (see the manifest), so it sits over Settings rather than
 * joining this app's task behind it. It never takes focus — the list underneath stays usable — and
 * it goes away on the first touch, after [AUTO_FINISH_MS], or when the caller comes back to front.
 */
class RoleHintActivity : AppCompatActivity() {

    private val autoFinish = Runnable { if (!isFinishing) finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.screen_home_hint)
        GuardRail.log(TAG, "hint shown over the home-app list")

        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

        val root = findViewById<View>(R.id.llMain)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // The row mirrors the entry the user is looking for: this app's own icon and label.
        runCatching {
            findViewById<ImageView>(R.id.guideRowIconIv)
                ?.setImageDrawable(packageManager.getApplicationIcon(applicationInfo))
            findViewById<TextView>(R.id.guideRowNameTv)?.text =
                packageManager.getApplicationLabel(applicationInfo).toString().trim()
        }

        bindDismissOnTouch(root)
        startTapAnimation()

        visible = this
        root.postDelayed(autoFinish, AUTO_FINISH_MS)
    }

    /**
     * The pointing-hand tap and the radio's pulse, in phase, so the row reads as "tap this one".
     * Null-safe so a restyle that drops either view cannot crash the hint.
     */
    private fun startTapAnimation() {
        findViewById<View>(R.id.hintHand)
            ?.startAnimation(AnimationUtils.loadAnimation(this, R.anim.hand_tap_loop))
        findViewById<View>(R.id.hintRadio)
            ?.startAnimation(AnimationUtils.loadAnimation(this, R.anim.tap_ring_pulse))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindDismissOnTouch(root: View) {
        root.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && !isFinishing) finish()
            true
        }
    }

    override fun onDestroy() {
        findViewById<View>(R.id.llMain)?.removeCallbacks(autoFinish)
        if (visible === this) visible = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DefaultHomeHint"

        /** Long enough to find one row in a list of launchers. */
        private const val AUTO_FINISH_MS = 12_000L

        private var visible: RoleHintActivity? = null

        fun intent(context: Context): Intent =
            Intent(context, RoleHintActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun dismiss() {
            visible?.takeUnless { it.isFinishing }?.finish()
            visible = null
        }
    }
}
