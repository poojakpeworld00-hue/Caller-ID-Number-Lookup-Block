package io.launcher.home.activities

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.provider.Telephony
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import io.launcher.home.R
import io.launcher.home.api.LauncherRegistry
import timber.log.Timber

/**
 * The hint card shown over a system screen this app has just sent the user to — the overlay page,
 * the Home-app list, the default-SMS chooser. It names what they are looking for and mimics the
 * control they have to work, flipping itself on over and over, then takes itself away.
 *
 * **The self-dismiss is load-bearing, not decoration.** This is a translucent Activity on top of a
 * system screen, and an Activity on top *pauses* the one underneath — on some OEM builds a paused
 * Settings page never finishes loading its list, so a card that stayed up would hide the very
 * control it is pointing at. It leaves after the kind's own timeout, on a tap, and the moment the
 * thing it is asking for is done.
 */
class DefaultHomeHintPanel : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var switchAnimator: Animator? = null
    private lateinit var kind: Kind

    /**
     * What the card is asking for. Each answers for itself when it is satisfied, so the card can get
     * out of the way the instant the user has done the thing.
     */
    enum class Kind(
        val title: Int,
        val description: Int,
        /** Where the card sits. See [Position] — this is not cosmetic on the SMS chooser. */
        val position: Position,
        /** What the card mimes. See [Control] — the wrong one points at nothing that is there. */
        val control: Control,
        val dismissAfterMs: Long,
    ) {
        OVERLAY(
            R.string.launcher_hint_overlay_title,
            R.string.launcher_hint_overlay_description,
            Position.BOTTOM,
            Control.SWITCH,
            3_000L,
        ),
        DEFAULT_HOME(
            R.string.launcher_hint_default_home_title,
            R.string.launcher_hint_default_home_description,
            Position.BOTTOM,
            Control.RADIO,
            3_000L,
        ),
        DEFAULT_SMS(
            R.string.launcher_hint_default_sms_title,
            R.string.launcher_hint_default_sms_description,
            Position.TOP,
            Control.NONE,
            2_500L,
        ),
        ;

        fun isSatisfied(context: Context): Boolean = when (this) {
            OVERLAY ->
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

            DEFAULT_HOME -> runCatching {
                val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                context.packageManager.resolveActivity(home, 0)
                    ?.activityInfo?.packageName == context.packageName
            }.getOrDefault(false)

            DEFAULT_SMS -> runCatching {
                Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
            }.getOrDefault(false)
        }
    }

    /**
     * BOTTOM is a full-screen window with the card resting at the foot of it — right for a Settings
     * *page*, where nothing the user needs is down there.
     *
     * TOP is for the system's default-SMS chooser, which is a dialog with its buttons at the bottom.
     * A card down there would land on "Set as default", so this one goes above and its window is
     * only as tall as the card and non-focusable, leaving the dialog focused and every touch outside
     * the card going straight to it.
     */
    enum class Position { TOP, BOTTOM }

    /**
     * The control the card mimes, which has to be the one actually on the screen behind it.
     *
     * The overlay page is a list of switches; the Home-app page is a single-choice list of radio
     * buttons. The SMS chooser is a dialog that names this app and offers a button — there is no
     * per-app control there to mime, so that card is words only rather than pointing at something
     * that does not exist.
     */
    enum class Control { SWITCH, RADIO, NONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kind = runCatching { Kind.valueOf(intent?.getStringExtra(EXTRA_KIND).orEmpty()) }
            .getOrElse {
                finish()
                return
            }

        setContentView(R.layout.lnch_default_home_hint_panel)
        applyKind()

        findViewById<View>(R.id.llMainUi)?.setOnClickListener { finish() }
        when (kind.control) {
            Control.SWITCH -> startSwitchAnimation()
            Control.RADIO -> startRadioAnimation()
            Control.NONE -> Unit
        }

        handler.postDelayed({
            if (!isFinishing) finish()
        }, kind.dismissAfterMs)
        handler.post(watchForDone)
    }

    private fun applyKind() {
        // The row the user is hunting for is labelled with the app's name, so the card says the same
        // name rather than "this app" — and it takes it from the same place Settings does, the
        // application label, which is not always `app_name`. Kinds whose title takes no argument
        // simply ignore it.
        val label = runCatching { applicationInfo.loadLabel(packageManager).toString() }
            .getOrElse { getString(R.string.launcher_app_launcher_name) }
        findViewById<android.widget.TextView>(R.id.guideTitleUi)?.text = getString(kind.title, label)
        findViewById<android.widget.TextView>(R.id.guideDescriptionUi)?.setText(kind.description)
        findViewById<android.widget.TextView>(R.id.guideMockAppLabelUi)?.text = label
        findViewById<android.widget.TextView>(R.id.guideMockOverlayLabelUi)?.text = label

        // Each system page gets a mock of itself with this app's row lifted out - the Home-app
        // list with the row selected, the overlay page with the row's switch on. The SMS chooser,
        // which names this app and offers a button, gets words only.
        findViewById<View>(R.id.guideHomeMockUi)?.visibility =
            if (kind.control == Control.RADIO) View.VISIBLE else View.GONE
        findViewById<View>(R.id.guideOverlayMockUi)?.visibility =
            if (kind.control == Control.SWITCH) View.VISIBLE else View.GONE
        findViewById<View>(R.id.guideRowUi)?.visibility =
            if (kind.control == Control.NONE) View.VISIBLE else View.GONE

        if (kind.position == Position.TOP) {
            findViewById<View>(R.id.guideSpacerUi)?.visibility = View.GONE
            window.setGravity(Gravity.TOP)
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
            // Focus stays with the chooser, so its buttons keep working while the card is up.
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }

    /**
     * Leaves the instant the user has done what the card is asking for, without waiting out the
     * timeout — by then the app is being brought back, and a card still asking would be sitting
     * over the screen they were returned to.
     */
    private val watchForDone = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            if (kind.isSatisfied(this@DefaultHomeHintPanel)) {
                finish()
                return
            }
            handler.postDelayed(this, CHECK_MS)
        }
    }

    /**
     * Runs the switch from off to on and back, forever: the thumb slides while the track fades
     * between the two colours, both on the same clock so they read as one movement.
     */
    private fun startSwitchAnimation() {
        val track = findViewById<FrameLayout>(R.id.guideSwitchTrackUi) ?: return
        val thumb = findViewById<View>(R.id.guideSwitchThumbUi) ?: return
        val row = findViewById<View>(R.id.guideMockOverlayRowUi)

        val travel =
            (TRACK_WIDTH_DP - THUMB_SIZE_DP - THUMB_INSET_DP * 2) * resources.displayMetrics.density

        val slide = ObjectAnimator.ofFloat(thumb, View.TRANSLATION_X, 0f, travel).apply {
            duration = FLIP_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }

        val tint = ValueAnimator.ofObject(ArgbEvaluator(), getColor(R.color.hint_switch_off), radioOn()).apply {
            duration = FLIP_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                track.backgroundTintList = ColorStateList.valueOf(animator.animatedValue as Int)
            }
        }

        switchAnimator = AnimatorSet().apply {
            // A pause at each end, so it reads as "flip this" rather than as a busy indicator.
            startDelay = HOLD_MS
            playTogether(listOf(slide, tint) + lift(row))
            start()
        }
    }

    /**
     * The whole mock row lifts a touch as its control turns on, on the control's clock: the tap
     * lands, the row answers. Empty when there is no row to lift.
     */
    private fun lift(row: View?): List<Animator> = row?.let {
        listOf(
            ObjectAnimator.ofFloat(it, View.SCALE_X, 1f, ROW_LIFT_SCALE),
            ObjectAnimator.ofFloat(it, View.SCALE_Y, 1f, ROW_LIFT_SCALE),
            ObjectAnimator.ofFloat(it, View.TRANSLATION_Y, 0f, -ROW_LIFT_DP * resources.displayMetrics.density),
        ).map { animator ->
            animator.apply {
                duration = FLIP_MS
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
            }
        }
    }.orEmpty()

    /**
     * Runs the radio button from unselected to selected and back, forever: the dot grows from
     * nothing while the ring and the dot fade between the two colours, all on the same clock so they
     * read as one movement.
     */
    private fun startRadioAnimation() {
        val ring = findViewById<View>(R.id.guideRadioRingUi) ?: return
        val dot = findViewById<View>(R.id.guideRadioDotUi) ?: return
        val row = findViewById<View>(R.id.guideMockAppRowUi)

        // Starts invisible rather than small-but-there: an unselected radio button has no centre at
        // all, and a permanent dot would read as "already chosen".
        dot.scaleX = 0f
        dot.scaleY = 0f
        dot.alpha = 0f

        val grow = listOf(
            ObjectAnimator.ofFloat(dot, View.SCALE_X, 0f, 1f),
            ObjectAnimator.ofFloat(dot, View.SCALE_Y, 0f, 1f),
            ObjectAnimator.ofFloat(dot, View.ALPHA, 0f, 1f),
        ).map { animator ->
            animator.apply {
                duration = FLIP_MS
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
            }
        }

        val tint = ValueAnimator.ofObject(ArgbEvaluator(), radioOff(), radioOn()).apply {
            duration = FLIP_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val color = ColorStateList.valueOf(animator.animatedValue as Int)
                ring.backgroundTintList = color
                dot.backgroundTintList = color
            }
        }

        switchAnimator = AnimatorSet().apply {
            // A pause at each end, so it reads as "choose this" rather than as a busy indicator.
            startDelay = HOLD_MS
            playTogether(grow + tint + lift(row))
            start()
        }
    }

    /** The mimic controls' resting / selected colours, light or dark from launcher_hint_colors. */
    private fun radioOff(): Int = getColor(R.color.hint_mock_row_ring)
    private fun radioOn(): Int = getColor(R.color.hint_accent)

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        switchAnimator?.cancel()
        switchAnimator = null
        super.onDestroy()
    }

    companion object {

        private const val TAG = "OverlayFlow"
        private const val EXTRA_KIND = "hint_kind"

        /** Over the system "display over other apps" page. */
        fun showForOverlay(activity: Activity) = show(activity, Kind.OVERLAY)

        /** Over the system "Default home app" list. */
        fun showForDefaultHome(activity: Activity) = show(activity, Kind.DEFAULT_HOME)

        /** The launcher only uses the default-home coach-mark. */
        fun show(activity: Activity) = show(activity, Kind.DEFAULT_HOME)

        /** Over the system default-SMS chooser. */
        fun showForDefaultSms(activity: Activity) = show(activity, Kind.DEFAULT_SMS)

        /**
         * Puts the card up over the system screen [activity] has just opened. Call it immediately
         * after `startActivity`/`startActivityForResult` — the delay is handled here.
         *
         * Two details decide whether it lands on screen at all:
         *
         *  - **The delay.** The card has to follow the system screen, not race it. Launched together
         *    it lands underneath and the user never sees it.
         *  - **NEW_TASK.** With the manifest's empty `taskAffinity`, this puts the card in a task of
         *    its own, stacked on the system screen's task. Without it the card joins this app's task,
         *    which drags the whole app back to the front — the card ends up over our own screen.
         */
        fun show(activity: Activity, kind: Kind) {
            // A no-op once the thing is already done: these pages can be opened by a user who only
            // wants to look, and a card telling them to do what they have already done is noise.
            if (kind.isSatisfied(activity)) return

            val handler = Handler(Looper.getMainLooper())
            val startedAt = SystemClock.elapsedRealtime()
            handler.postDelayed(object : Runnable {
                override fun run() {
                    if (activity.isFinishing || activity.isDestroyed) return
                    if (kind.isSatisfied(activity)) return

                    val waited = SystemClock.elapsedRealtime() - startedAt
                    if (activity.hasWindowFocus() && waited < GIVE_UP_MS) {
                        handler.postDelayed(this, POLL_MS)
                        return
                    }
                    if (activity.hasWindowFocus()) {
                        Timber.tag(TAG).w("%s screen never came up — no hint", kind)
                        return
                    }
                    handler.postDelayed({ launch(activity, kind) }, SETTLE_MS)
                }
            }, FIRST_CHECK_MS)
        }

        private fun launch(activity: Activity, kind: Kind) {
            if (activity.isFinishing || activity.isDestroyed) return
            if (kind.isSatisfied(activity)) return
            runCatching {
                // The host's own default-home hint when it has one, so onboarding and the launcher
                // show the same card; this panel otherwise.
                val hostHint = if (kind == Kind.DEFAULT_HOME) {
                    LauncherRegistry.bridge.defaultHomeHintIntent(activity)
                } else {
                    null
                }
                activity.startActivity(
                    (hostHint ?: Intent(activity, DefaultHomeHintPanel::class.java)
                        .putExtra(EXTRA_KIND, kind.name))
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION,
                        ),
                )
                Timber.tag(TAG).d("hint shown: %s", kind)
            }.onFailure { Timber.tag(TAG).w(it, "could not show the %s hint", kind) }
        }

        /**
         * The card must go up *after* the system screen, never before it — put up first it lands
         * underneath, and `noHistory` then finishes it the moment that screen draws over it.
         *
         * A fixed delay cannot do this. The overlay Settings page appears in a few hundred
         * milliseconds; the Home-app page resolves on some builds to the permission-controller role
         * UI, which took over three seconds on a mid-range device — so the delay that works for one
         * misses the other. What is reliable is the host losing window focus: that happens exactly
         * when the system screen takes over, whatever it is and however slow it was.
         */
        private const val FIRST_CHECK_MS = 250L
        private const val POLL_MS = 150L

        /** A beat after the system screen has focus, so the card lands on a settled screen. */
        private const val SETTLE_MS = 350L

        /** Nothing took over — the page never opened, so there is nothing to point at. */
        private const val GIVE_UP_MS = 6_000L

        /** How often the card re-reads its [Kind.isSatisfied] while it is up. */
        private const val CHECK_MS = 300L

        /** One flip, off to on. The reverse repeat makes the round trip twice this. */
        private const val FLIP_MS = 700L
        private const val HOLD_MS = 250L

        // Must match the switch in permission_hint_activity.xml.
        private const val TRACK_WIDTH_DP = 52f
        private const val THUMB_SIZE_DP = 26f
        private const val THUMB_INSET_DP = 3f

        /** How far the mock row lifts as its radio fills. */
        private const val ROW_LIFT_SCALE = 1.04f
        private const val ROW_LIFT_DP = 3f
    }
}
