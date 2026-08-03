package com.calleridapp.numberlookup.ui.tools

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.base.HostActivity
import com.calleridapp.admesh.presentation.NativePromo
import com.calleridapp.numberlookup.databinding.ActivityStopwatchBinding
import com.calleridapp.numberlookup.databinding.ItemLapBinding
import java.util.Locale

/** Stopwatch with lap recording. */
class ChronoActivity : HostActivity<ActivityStopwatchBinding>() {

    override val layoutId: Int = R.layout.activity_stopwatch

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var accumulatedMs = 0L
    private var startRealtime = 0L
    private var lastLapTotal = 0L
    private var lapCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.stopwatchRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.btnBack.setOnClickListener { goBack() }

        // Mid native, scrolls with the tool content.
        NativePromo().showMidNative(this, binding.adNativeFrame, binding.adShimmer)
        binding.btnStartPause.setOnClickListener { if (running) pause() else start() }
        binding.btnReset.setOnClickListener { reset() }
        binding.btnLap.setOnClickListener { lap() }

        reset()
    }

    override fun onPause() {
        super.onPause()
        if (running) pause()
    }

    private fun start() {
        running = true
        startRealtime = SystemClock.elapsedRealtime()
        binding.btnStartPause.setText(R.string.action_pause)
        binding.btnStartPause.setIconResource(R.drawable.ic_pause)
        binding.btnLap.isEnabled = true
        handler.post(tick)
    }

    private fun pause() {
        running = false
        handler.removeCallbacks(tick)
        accumulatedMs += SystemClock.elapsedRealtime() - startRealtime
        binding.btnStartPause.setText(R.string.action_start)
        binding.btnStartPause.setIconResource(R.drawable.ic_play)
        binding.btnLap.isEnabled = false
        renderTime()
    }

    private fun reset() {
        running = false
        handler.removeCallbacks(tick)
        accumulatedMs = 0L
        lastLapTotal = 0L
        lapCount = 0
        binding.btnStartPause.setText(R.string.action_start)
        binding.btnStartPause.setIconResource(R.drawable.ic_play)
        binding.btnLap.isEnabled = false
        binding.llLaps.removeAllViews()
        binding.llLaps.visibility = View.GONE
        binding.emptyState.visibility = View.VISIBLE
        binding.tvLapCount.text = getString(R.string.stopwatch_laps, 0)
        renderTime()
    }

    private fun lap() {
        if (!running) return
        val total = elapsed()
        val split = total - lastLapTotal
        lastLapTotal = total
        lapCount++

        val row = ItemLapBinding.inflate(LayoutInflater.from(this), binding.llLaps, false)
        row.tvLapName.text = getString(R.string.stopwatch_lap_n, lapCount)
        row.tvLapSplit.text = format(split)
        row.tvLapTotal.text = format(total)
        binding.llLaps.addView(row.root, 0) // newest on top

        binding.llLaps.visibility = View.VISIBLE
        binding.emptyState.visibility = View.GONE
        binding.tvLapCount.text = getString(R.string.stopwatch_laps, lapCount)
    }

    private fun elapsed(): Long =
        accumulatedMs + if (running) SystemClock.elapsedRealtime() - startRealtime else 0L

    private val tick = object : Runnable {
        override fun run() {
            renderTime()
            handler.postDelayed(this, 30)
        }
    }

    private fun renderTime() {
        val text = format(elapsed())
        binding.tvTime.text = text
        binding.tvTotal.text = text
    }

    /** mm:ss.cc (centiseconds). */
    private fun format(ms: Long): String {
        val totalSec = ms / 1000
        val cs = (ms % 1000) / 10
        return String.format(Locale.getDefault(), "%02d:%02d.%02d", totalSec / 60, totalSec % 60, cs)
    }
}
