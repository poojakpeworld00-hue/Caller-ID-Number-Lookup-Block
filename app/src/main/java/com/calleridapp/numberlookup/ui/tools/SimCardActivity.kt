package com.calleridapp.numberlookup.ui.tools

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.telephony.TelephonyManager
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.base.HostActivity
import com.calleridapp.admesh.presentation.NativePromo
import com.calleridapp.numberlookup.databinding.ActivitySimInfoBinding
import java.util.Locale

/** Carrier / SIM / network details from [TelephonyManager]. */
class SimCardActivity : HostActivity<ActivitySimInfoBinding>() {

    override val layoutId: Int = R.layout.activity_sim_info

    private val tm by lazy { getSystemService(TELEPHONY_SERVICE) as TelephonyManager }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.simRoot) { v, insets ->
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
        if (hasPhonePermission()) render()
        else permissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
    }

    private fun hasPhonePermission(): Boolean = ContextCompat.checkSelfPermission(
        this, Manifest.permission.READ_PHONE_STATE
    ) == PackageManager.PERMISSION_GRANTED

    private fun render() {
        // Permission-gated fields show a hint; the rest always populate.
        binding.tvStatus.visibility = if (hasPhonePermission()) View.GONE else View.VISIBLE

        binding.tvCarrier.text = tm.networkOperatorName.ifBlank { dash() }
        binding.tvNetworkType.text = networkTypeText()
        binding.tvPhoneType.text = phoneTypeText()
        binding.tvCountry.text = tm.networkCountryIso.uppercase(Locale.getDefault()).ifBlank { dash() }
        binding.tvSimState.text = simStateText()
        binding.tvRoaming.text =
            if (tm.isNetworkRoaming) getString(R.string.common_yes) else getString(R.string.common_no)
    }

    private fun networkTypeText(): String {
        if (!hasPhonePermission()) return dash()
        return runCatching {
            when (tm.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
                TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA -> "3G"
                TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
                TelephonyManager.NETWORK_TYPE_UNKNOWN -> getString(R.string.common_unknown)
                else -> getString(R.string.common_unknown)
            }
        }.getOrElse { dash() }
    }

    private fun phoneTypeText() = when (tm.phoneType) {
        TelephonyManager.PHONE_TYPE_GSM -> "GSM"
        TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
        TelephonyManager.PHONE_TYPE_SIP -> "SIP"
        else -> getString(R.string.common_unknown)
    }

    private fun simStateText() = when (tm.simState) {
        TelephonyManager.SIM_STATE_READY -> "Ready"
        TelephonyManager.SIM_STATE_ABSENT -> "No SIM"
        TelephonyManager.SIM_STATE_PIN_REQUIRED,
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "Locked"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Network locked"
        else -> getString(R.string.common_unknown)
    }

    private fun dash() = "—"
}
