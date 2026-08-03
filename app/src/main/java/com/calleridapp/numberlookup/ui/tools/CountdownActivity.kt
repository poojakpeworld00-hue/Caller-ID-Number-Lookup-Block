package com.calleridapp.numberlookup.ui.tools

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.base.HostActivity
import com.calleridapp.admesh.presentation.NativePromo
import com.calleridapp.numberlookup.databinding.ActivityTimerBinding
import java.util.Locale

/** Countdown timer with +1:00 / +0:10 / +0:01 presets. */
class CountdownActivity : HostActivity<ActivityTimerBinding>() {

    override val layoutId: Int = R.layout.activity_timer

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var remainingMs = 0L
    private var endRealtime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.timerRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.btnBack.setOnClickListener { goBack() }

        // Mid native, scrolls with the tool content.
        NativePromo().showMidNative2(this, binding.adNativeFrame, binding.adShimmer)
        binding.btnStartPause.setOnClickListener { if (running) pause() else start() }
        binding.btnReset.setOnClickListener { reset() }
        binding.btnAddMin.setOnClickListener { add(60_000) }
        binding.btnAddTenSec.setOnClickListener { add(10_000) }
        binding.btnAddSec.setOnClickListener { add(1_000) }

        renderTime()
    }

    override fun onPause() {
        super.onPause()
        if (running) pause()
    }

    private fun add(deltaMs: Long) {
        if (running) return
        remainingMs += deltaMs
        renderTime()
    }

    private fun start() {
        if (remainingMs <= 0L) return
        running = true
        endRealtime = SystemClock.elapsedRealtime() + remainingMs
        binding.btnStartPause.setText(R.string.action_pause)
        binding.btnStartPause.setIconResource(R.drawable.ic_pause)
        setPresetsEnabled(false)
        handler.post(tick)
    }

    private fun pause() {
        running = false
        handler.removeCallbacks(tick)
        remainingMs = (endRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        binding.btnStartPause.setText(R.string.action_start)
        binding.btnStartPause.setIconResource(R.drawable.ic_play)
        setPresetsEnabled(true)
        renderTime()
    }

    private fun reset() {
        running = false
        handler.removeCallbacks(tick)
        remainingMs = 0L
        binding.btnStartPause.setText(R.string.action_start)
        binding.btnStartPause.setIconResource(R.drawable.ic_play)
        setPresetsEnabled(true)
        renderTime()
    }

    private val tick = object : Runnable {
        override fun run() {
            val remaining = endRealtime - SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                remainingMs = 0L
                renderTime()
                onFinished()
            } else {
                remainingMs = remaining
                renderTime()
                handler.postDelayed(this, 100)
            }
        }
    }

    private fun onFinished() {
        running = false
        binding.btnStartPause.setText(R.string.action_start)
        binding.btnStartPause.setIconResource(R.drawable.ic_play)
        setPresetsEnabled(true)
        vibrate()
    }

    private fun renderTime() {
        val totalSec = (remainingMs + 999) / 1000 // round up while counting down
        binding.tvTime.text =
            String.format(Locale.getDefault(), "%02d:%02d", totalSec / 60, totalSec % 60)
    }

    private fun setPresetsEnabled(enabled: Boolean) {
        binding.btnAddMin.isEnabled = enabled
        binding.btnAddTenSec.isEnabled = enabled
        binding.btnAddSec.isEnabled = enabled
        val alpha = if (enabled) 1f else 0.5f
        binding.btnAddMin.alpha = alpha
        binding.btnAddTenSec.alpha = alpha
        binding.btnAddSec.alpha = alpha
    }

    private fun vibrate() {
        val vibrator = ContextCompat.getSystemService(this, Vibrator::class.java) ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
