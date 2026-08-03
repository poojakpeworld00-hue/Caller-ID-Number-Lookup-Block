package com.calleridapp.numberlookup.ui.tools

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.base.HostActivity
import com.calleridapp.admesh.presentation.NativePromo
import com.calleridapp.numberlookup.databinding.ActivitySoundMeterBinding
import java.io.File
import java.util.Locale
import kotlin.math.log10
import kotlin.math.roundToInt

/** Approximate sound-level meter using [MediaRecorder.getMaxAmplitude]. */
class DecibelActivity : HostActivity<ActivitySoundMeterBinding>() {

    override val layoutId: Int = R.layout.activity_sound_meter

    private var recorder: MediaRecorder? = null
    private val handler = Handler(Looper.getMainLooper())

    private var held = false
    private var peak = 0f
    private var sum = 0.0
    private var count = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startMetering() else binding.tvStatus.setText(R.string.sound_permission)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.soundRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.btnBack.setOnClickListener { goBack() }

        // Mid native, scrolls with the tool content.
        NativePromo().showMidNative2(this, binding.adNativeFrame, binding.adShimmer)
        binding.pbLevel.isIndeterminate = false
        binding.pbLevel.max = 100

        binding.btnHold.setOnClickListener {
            held = !held
            binding.btnHold.setText(if (held) R.string.sound_resume else R.string.sound_hold)
        }
        binding.btnReset.setOnClickListener { resetData() }
    }

    override fun onResume() {
        super.onResume()
        if (hasMicPermission()) startMetering()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onPause() {
        super.onPause()
        stopMetering()
    }

    private fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun startMetering() {
        if (recorder != null || !hasMicPermission()) return
        binding.tvStatus.text = ""
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
        else @Suppress("DEPRECATION") MediaRecorder()
        runCatching {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            rec.setOutputFile(File(cacheDir, "sound_meter.tmp").absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            handler.post(tick)
        }.onFailure { rec.release() }
    }

    private fun stopMetering() {
        handler.removeCallbacks(tick)
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
    }

    private fun resetData() {
        peak = 0f
        sum = 0.0
        count = 0L
        binding.spectrum.reset()
        binding.tvPeak.text = formatDb(0f)
        binding.tvAvg.text = formatDb(0f)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!held) {
                val amplitude = recorder?.maxAmplitude ?: 0
                val db = (if (amplitude > 0) 20 * log10(amplitude.toDouble()) else 0.0)
                    .toFloat().coerceIn(0f, 100f)
                render(db)
            }
            handler.postDelayed(this, 200)
        }
    }

    private fun render(db: Float) {
        binding.pbLevel.setProgressCompat(db.roundToInt(), true)
        binding.tvDb.text = db.roundToInt().toString()
        binding.tvCategory.setText(categoryRes(db))

        if (db > peak) {
            peak = db
            binding.tvPeak.text = formatDb(peak)
        }
        sum += db
        count++
        binding.tvAvg.text = formatDb((sum / count).toFloat())

        binding.spectrum.setLevel(db / 100f)
    }

    private fun formatDb(db: Float) = String.format(Locale.getDefault(), "%.1f dB", db)

    private fun categoryRes(db: Float) = when {
        db < 40f -> R.string.sound_cat_quiet
        db < 60f -> R.string.sound_cat_normal
        db < 75f -> R.string.sound_cat_loud
        db < 90f -> R.string.sound_cat_very_loud
        else -> R.string.sound_cat_dangerous
    }
}
