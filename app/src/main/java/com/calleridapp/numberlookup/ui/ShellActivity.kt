package com.calleridapp.numberlookup.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.base.HostActivity
import com.calleridapp.numberlookup.databinding.ActivityMainBinding
import com.calleridapp.numberlookup.launcher.activities.MainActivity as LauncherHomeActivity
import com.calleridapp.numberlookup.ui.home.HomeShellController
import com.calleridapp.numberlookup.ui.home.HomeShellFragment
import com.calleridapp.numberlookup.ui.home.HomeShellHost

/**
 * The caller-ID app's own home screen. A thin host: the UI is [HomeShellFragment] and the
 * Activity-bound flows are [HomeShellController], so the launcher's swipe-right panel can show
 * the exact same shell (see the launcher's `CallerPanelFragment`) instead of a second copy.
 */
class ShellActivity : HostActivity<ActivityMainBinding>(), HomeShellHost {

    override val layoutId: Int = R.layout.activity_main

    override val hostActivity: AppCompatActivity get() = this

    // Not `by lazy`: constructing this registers result launchers, which must happen before
    // the Activity is STARTED.
    override val homeShellController = HomeShellController(this)

    /** The shell, once committed. */
    private val shell: HomeShellFragment?
        get() = supportFragmentManager.findFragmentById(R.id.shellContainer) as? HomeShellFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Registration only, pre-STARTED: update launcher, contact upload, FSI watcher.
        homeShellController.onHostCreated()
    }

    override fun initView() {
        // Bottom/side insets belong to the Activity here: padding the root lifts the shell
        // *and* the bottom ad banner clear of the navigation bar. The shell applies the top
        // inset itself, per tab.
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }

        if (shell == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.shellContainer,
                    HomeShellFragment.newInstance(consumeLookupNumber(intent))
                )
                .commitNow()
        }

        // Back retraces the shell's visited-tab stack; once that empties (on Home) a single
        // back leaves for the launcher home. Registered here — after HostActivity's back-ad
        // callback, which super.onCreate added — so this one is the more recent enabled
        // callback and therefore runs first. There is no press-back-again-to-exit step: this
        // screen is one swipe from the home screen, so backing out of it should feel like
        // closing a panel, not like quitting the app.
        onBackPressedDispatcher.addCallback(this) {
            if (shell?.onBackPressed() != true) onShellBackExhausted()
        }

        // Here the shell *is* the screen, so it is visible the moment it is committed.
        shell?.setPanelVisible(true)
        homeShellController.startFirstRunPriming()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeLookupNumber(intent)?.let { shell?.requestLookup(it) }
    }

    /** Reads and clears the "identify this number" extra (Call Details → Identify). */
    private fun consumeLookupNumber(intent: Intent?): String? {
        val number = intent?.getStringExtra(EXTRA_LOOKUP_NUMBER)?.takeIf { it.isNotBlank() }
        intent?.removeExtra(EXTRA_LOOKUP_NUMBER)
        return number
    }

    override fun onResume() {
        super.onResume()
        homeShellController.onHostResume()
    }

    override fun onDestroy() {
        homeShellController.onHostDestroy()
        super.onDestroy()
    }

    /**
     * Returns to the launcher home screen.
     *
     * Goes to it explicitly rather than firing a generic ACTION_MAIN/CATEGORY_HOME: this app is
     * only the default launcher once the user grants the role, and until then a HOME intent
     * would hand the user to whichever launcher is currently default. Starting the home screen
     * brings its own task forward — which also guarantees we never land on a leftover system
     * "Manage" Settings page (those are `singleTask`, live in their own task, and would
     * otherwise surface when this task finishes).
     */
    override fun onShellBackExhausted() {
        runCatching {
            startActivity(
                Intent(this, LauncherHomeActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            )
        }
        finishAffinity()
    }

    /**
     * Reorders the EXISTING ShellActivity to the front of the same task, so a (NO_HISTORY)
     * system Settings page drops away and the user lands back on their current tab without
     * pressing Back. In-task REORDER = no background-activity-start, so it needs no BAL
     * privilege on Android 12+/16.
     */
    override fun bringHostToFront() {
        runCatching {
            startActivity(
                Intent(this, ShellActivity::class.java)
                    .addFlags(HomeShellController.REORDER_FLAGS)
            )
        }
    }

    companion object {
        /** Intent extra: a number to identify — routes straight to the Lookup tab. */
        const val EXTRA_LOOKUP_NUMBER = "extra_lookup_number"
    }
}
