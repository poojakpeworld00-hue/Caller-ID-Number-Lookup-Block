package com.calleridapp.numberlookup.ui.tools

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.base.HostActivity
import com.calleridapp.admesh.presentation.NativePromo
import com.calleridapp.numberlookup.databinding.ActivityLightMeterBinding
import kotlin.math.roundToInt

/** Ambient light meter (lux) using the device light sensor. */
class LuxMeterActivity : HostActivity<ActivityLightMeterBinding>(), SensorEventListener {

    override val layoutId: Int = R.layout.activity_light_meter

    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null

    private var held = false
    private var min = Float.MAX_VALUE
    private var max = 0f
    private var sum = 0.0
    private var count = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.lightRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.btnBack.setOnClickListener { goBack() }

        // Mid native, scrolls with the tool content.
        NativePromo().showMidNative2(this, binding.adNativeFrame, binding.adShimmer)
        binding.pbLevel.isIndeterminate = false
        binding.pbLevel.max = 100

        binding.btnCapture.setOnClickListener {
            held = !held
            binding.btnCapture.setText(if (held) R.string.light_resume else R.string.light_capture)
        }
        binding.btnZero.setOnClickListener { resetStats() }
        resetStats()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (lightSensor == null) {
            binding.tvNoSensor.visibility = View.VISIBLE
            binding.content.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_LIGHT || held) return
        val lux = event.values[0]
        binding.tvLux.text = lux.roundToInt().toString()
        // Arc fills toward MAX_SCALE lux (bright indoor / overcast daylight).
        binding.pbLevel.setProgressCompat((lux / MAX_SCALE * 100f).roundToInt().coerceIn(0, 100), true)

        if (lux < min) min = lux
        if (lux > max) max = lux
        sum += lux
        count++

        binding.tvMin.text = lx(min)
        binding.tvMax.text = lx(max)
        binding.tvAvg.text = lx((sum / count).toFloat())
    }

    private fun resetStats() {
        min = Float.MAX_VALUE
        max = 0f
        sum = 0.0
        count = 0L
        binding.tvMin.text = lx(0f)
        binding.tvAvg.text = lx(0f)
        binding.tvMax.text = lx(0f)
    }

    private fun lx(value: Float): String {
        val v = if (value == Float.MAX_VALUE) 0 else value.roundToInt()
        return getString(R.string.light_lx, v)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private companion object {
        const val MAX_SCALE = 1000f
    }
}
