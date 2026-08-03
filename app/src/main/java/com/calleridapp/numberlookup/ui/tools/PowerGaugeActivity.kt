package com.calleridapp.numberlookup.ui.tools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.base.HostActivity
import com.calleridapp.admesh.presentation.NativePromo
import com.calleridapp.numberlookup.databinding.ActivityBatteryBinding
import java.util.Locale
import kotlin.math.roundToInt

/** Live battery stats, read from sticky ACTION_BATTERY_CHANGED broadcasts. */
class PowerGaugeActivity : HostActivity<ActivityBatteryBinding>() {

    override val layoutId: Int = R.layout.activity_battery

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let { render(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.batteryRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.btnBack.setOnClickListener { goBack() }

        // Mid native, scrolls with the tool content.
        NativePromo().showMidNative(this, binding.adNativeFrame, binding.adShimmer)
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(receiver) }
    }

    private fun render(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        binding.tvLevel.text = if (level >= 0 && scale > 0) (level * 100 / scale).toString() else "—"

        binding.tvStatus.text = statusText(intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1))
        binding.tvPlugged.text = pluggedText(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1))

        val tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        binding.tvTemp.text = String.format(Locale.getDefault(), "%d°C", tempC.roundToInt())

        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f
        binding.tvVoltage.text = String.format(Locale.getDefault(), "%.1fV", voltage)

        binding.tvTech.text = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "—"

        bindHealth(intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1))
    }

    /** Health value + a status-appropriate colour. */
    private fun bindHealth(health: Int) {
        val (textRes, colorRes) = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> R.string.battery_health_great to R.color.primary
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> R.string.battery_health_hot to R.color.warn
            BatteryManager.BATTERY_HEALTH_COLD -> R.string.battery_health_cold to R.color.primary
            BatteryManager.BATTERY_HEALTH_DEAD -> R.string.battery_health_dead to R.color.danger
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> R.string.battery_health_over to R.color.danger
            else -> R.string.common_unknown to R.color.on_surface
        }
        binding.tvHealth.setText(textRes)
        binding.tvHealth.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun statusText(status: Int) = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        else -> "Unknown"
    }

    private fun pluggedText(plugged: Int) = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
        0 -> "Battery"
        else -> "Unknown"
    }
}
