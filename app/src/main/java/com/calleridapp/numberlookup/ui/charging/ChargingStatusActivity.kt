package com.calleridapp.numberlookup.ui.charging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.calleridapp.admesh.domain.LauncherPlacementAds
import com.calleridapp.admesh.domain.AdCounterRegistry
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.domain.LauncherAdsConfig
import com.calleridapp.admesh.presentation.DirectLinkOpener
import com.calleridapp.admesh.presentation.DrawerAdRunner
import com.calleridapp.admesh.presentation.NativePromo
import com.calleridapp.admesh.presentation.NativePromoBanner
import com.calleridapp.admesh.presentation.oninterAds.InterstitialNormal
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.util.SystemDialogHelper
import com.calleridapp.numberlookup.util.applyNativeAdTheme

/**
 * The charging / discharging screen (ported from QRScanner's `ChargingStatusActivity`).
 *
 * Launched by [ChargeEventWatcher] on a power event, which has already passed every gate. It reads
 * the live battery (level, plug type, time-to-full) from `ACTION_BATTERY_CHANGED` and paints the
 * ring, the metrics and the status chip. `system_ads.<trigger>.native` fills the bottom slot
 * (`big` / `mid` / `mid2` / `native_banner`, QRScanner's `BigNative` / `MediumNative` /
 * `MediumNative2` / `NativeBanner` too, or `off`) and `close_ad` fires on the way out (`inter`,
 * `full_native`, `directlink` or `none`) — the same contract as [com.callerid.number.lookup.home.screen.recent.RecentAdActivity].
 */
class ChargingStatusActivity : AppCompatActivity() {

    private val trigger: String
        get() = intent.getStringExtra(EXTRA_TRIGGER) ?: TRIGGER_CHARGE

    private val settings by lazy { LauncherAdsConfig.systemAdSettings(this, trigger) }

    private var closed = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = render(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyNativeAdTheme()
        // Recents closes it: a charge/discharge screen is not something to come back to.
        lifecycle.addObserver(SystemDialogHelper(this) { finishAndRemoveTask() })
        showOverLockAndWake()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_charging_status)

        // Content under the transparent status bar; pad the functional block down past it.
        val content = findViewById<View>(R.id.chargingFunctionalContent)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.chargingRoot)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            content.updatePadding(top = bars.top)
            findViewById<View>(R.id.chargingAdSlot).updatePadding(bottom = bars.bottom)
            insets
        }

        findViewById<ImageButton>(R.id.btnCloseCharging).setOnClickListener { close() }
        onBackPressedDispatcher.addCallback(this) { close() }
        render(registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        fillAd()
        // Loaded while the user reads the screen, so it is ready when they close it.
        if (closesWithFullNative) DrawerAdRunner.preload(this, fullNativeFlow())
    }

    /** QRScanner's `close_ad: full_native` — a full-screen native, from the app-wide `googleNative`. */
    private val closesWithFullNative: Boolean
        get() = settings.closeAd.trim().lowercase() in setOf("full_native", "fullnative", "full")

    private fun fullNativeFlow() = LauncherAdsConfig.DrawerAdFlow(
        enabled = true,
        counter = 0,
        sequence = listOf(
            LauncherAdsConfig.DrawerAdSpec(
                LauncherAdsConfig.DrawerAdType.FULLSCREEN_NATIVE,
                AdsVault.getInstance(this).getString("googleNative").orEmpty(),
            )
        ),
        startFromFirst = true,
    )

    /** A second power event (this screen is singleTask) — re-create for the new charge/discharge. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onStop() {
        runCatching { unregisterReceiver(batteryReceiver) }
        super.onStop()
    }

    /** Reads the battery snapshot and paints both states. */
    private fun render(battery: Intent?) {
        val level = battery?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (l >= 0 && scale > 0) l * 100 / scale else 0
        } ?: 0
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val connected = plugged != 0

        findViewById<ChargingEnergyView>(R.id.chargingEnergyView).apply {
            setConnected(connected)
            setProgress(level, animate = true)
        }
        findViewById<TextView>(R.id.chargingPercentage).text =
            getString(R.string.charging_percentage_value, level)

        if (connected) {
            findViewById<TextView>(R.id.chargingModeLabel).setText(R.string.charging_live_energy)
            findViewById<TextView>(R.id.chargingTitle).setText(R.string.charging_connected_title)
            findViewById<TextView>(R.id.chargingSubtitle).text =
                getString(R.string.charging_connected_subtitle, sourceLabel(plugged))
            findViewById<TextView>(R.id.chargingMetricLeftLabel).setText(R.string.charging_until_full)
            findViewById<TextView>(R.id.chargingMetricLeftValue).text = timeToFull(level)
            findViewById<TextView>(R.id.chargingMetricRightLabel).setText(R.string.charging_power_source)
            findViewById<TextView>(R.id.chargingMetricRightValue).text = sourceLabel(plugged)
            findViewById<TextView>(R.id.chargingStatusText).setText(R.string.charging_power_flow_active)
            findViewById<View>(R.id.chargingInfoCard).visibility = View.VISIBLE
        } else {
            findViewById<TextView>(R.id.chargingModeLabel).setText(R.string.charging_session_complete)
            findViewById<TextView>(R.id.chargingTitle).setText(R.string.charging_disconnected_title)
            findViewById<TextView>(R.id.chargingSubtitle).text =
                getString(R.string.charging_ready_to_use, level)
            findViewById<TextView>(R.id.chargingStatusText).setText(R.string.charging_disconnected_safely)
            findViewById<View>(R.id.chargingInfoCard).visibility = View.GONE
        }
    }

    /** The system's charge-time estimate (API 28+), formatted; "Calculating…"/"Full" otherwise. */
    private fun timeToFull(level: Int): String {
        if (level >= 100) return getString(R.string.charging_time_full)
        val remainingMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            (getSystemService(BATTERY_SERVICE) as? BatteryManager)?.computeChargeTimeRemaining() ?: -1L
        } else {
            -1L
        }
        if (remainingMs <= 0L) return getString(R.string.charging_time_calculating)
        val minutes = maxOf(1L, (remainingMs + 30_000) / 60_000)
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) {
            getString(R.string.charging_time_hours_minutes, h, m)
        } else {
            getString(R.string.charging_time_minutes, m)
        }
    }

    private fun sourceLabel(plugged: Int): String = when {
        plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 ->
            getString(R.string.charging_source_wireless)
        plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> getString(R.string.charging_source_usb)
        plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> getString(R.string.charging_source_ac)
        else -> getString(R.string.charging_source_power)
    }

    private fun fillAd() {
        if (!settings.showsBodyNative) return
        val slot = findViewById<FrameLayout>(R.id.chargingAdSlot)
        slot.visibility = View.VISIBLE
        when (settings.nativeType.trim().lowercase()) {
            "big", "big_native", "bignative" -> NativePromo().showBigNative(this, slot)
            "mid2", "mid_native2", "midnative2", "mediumnative2" -> NativePromo().showMidNative2(this, slot)
            "native_banner", "nativebanner" -> NativePromoBanner().showNativeBannerNative(this, slot)
            else -> NativePromo().showMidNative(this, slot)
        }
    }

    /**
     * Closes the screen, showing the configured close ad first. The screen closes either way — the
     * close ad never becomes a reason the user cannot leave.
     */
    private fun close() {
        if (closed) return
        closed = true
        findViewById<ImageButton>(R.id.btnCloseCharging).isEnabled = false
        // `charge_*` / `discharge_*` (`ad_flow` or a link chain) override `close_ad` when set.
        if (LauncherPlacementAds.hasOwnFlow(this, trigger)) {
            LauncherPlacementAds.showInterstitial(this, trigger) { finishSafely() }
            return
        }
        when {
            settings.closeShowsInterstitial -> {
                // This screen has its own throttle (min_gap_sec); the app-wide InterCounter on top
                // would skip the one ad it exists to show.
                AdCounterRegistry.interCounter = AdsVault.getInstance(this).getInt("InterCounter")
                InterstitialNormal().showInterAds(this) { finishSafely() }
            }
            // Shows only if it finished loading; either way the screen closes after.
            closesWithFullNative ->
                DrawerAdRunner.run(this, fullNativeFlow(), "__charging_full_native_ptr") { finishSafely() }
            settings.closeShowsLink -> {
                DirectLinkOpener.open(this, LauncherAdsConfig.directLink(this))
                finishSafely()
            }
            else -> finishSafely()
        }
    }

    private fun finishSafely() {
        if (!isFinishing) finish()
    }

    private fun showOverLockAndWake() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    companion object {
        const val EXTRA_TRIGGER = "system_ad_trigger"
        const val TRIGGER_CHARGE = "charge"
        const val TRIGGER_DISCHARGE = "discharge"
    }
}
