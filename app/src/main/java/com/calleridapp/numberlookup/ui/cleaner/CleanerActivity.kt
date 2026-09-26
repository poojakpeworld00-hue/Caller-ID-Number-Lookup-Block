package com.calleridapp.numberlookup.ui.cleaner

import android.os.StatFs
import android.text.format.Formatter
import com.calleridapp.admesh.presentation.RewardedPromo
import com.calleridapp.admesh.presentation.NativePromo
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.databinding.ScreenCleanerBinding
import com.calleridapp.numberlookup.base.HostActivity
import java.io.File

/**
 * The "Free up space" page opened by the home's right edge pill (this app's analogue of the
 * reference launcher's cleaner panel). It reports what its own cache can free, sweeps it on the
 * CTA, and shows the device-storage figure alongside.
 *
 * Scope is deliberately this app's own `cacheDir`/`externalCacheDir` — the whole of what an app
 * may clear without special permissions. It opens on "here is what can be freed" with the action
 * still to take, then switches to the freed verdict once the button is pressed.
 */
class CleanerActivity : HostActivity<ScreenCleanerBinding>() {

    override val layoutId: Int = R.layout.screen_cleaner

    private var cleaned = false

    /** True from the CTA tap until the rewarded ad and sweep finish (or fail). Locks the button. */
    private var busy = false

    override fun initView() {
        binding.cleanerBackVw.setOnClickListener { goBack() }
        renderStorage()
        renderFound(sweepable())
        binding.cleanerActionVw.setOnClickListener { onActionClicked() }
        NativePromo().showMidNative(this, binding.adNativeFrame, binding.adShimmer)

        // Warm a rewarded ad so the CTA can gate the sweep on it without a visible load stall.
        RewardedPromo.preload(this)
    }

    private fun onActionClicked() {
        // One press at a time: the guard blocks duplicate taps (and a second rewarded-ad trigger)
        // while the ad is on screen and the sweep runs.
        if (busy) return

        if (cleaned) {
            goBack()
            return
        }

        // Nothing to free — leave the button in its disabled "all clear" state, do not gate an ad.
        if (sweepable().files == 0) return

        busy = true
        lockAction()

        // Show the rewarded ad, then sweep. BonusPromo.show invokes this callback on dismiss, on a
        // show/load failure (after its fallback), and when ads are off — i.e. once the flow is done
        // either way — so this is the single place the sweep runs and the button unlocks.
        RewardedPromo().show(this) {
            if (isFinishing || isDestroyed) return@show
            performSweep()
            busy = false
        }
    }

    /** Frees this app's caches and switches the page to the "cleaned" verdict. */
    private fun performSweep() {
        val freed = sweepable()
        runCatching {
            cacheDir?.deleteRecursively()
            externalCacheDir?.deleteRecursively()
        }
        cleaned = true
        renderCleaned(freed)
        renderStorage()
    }

    /** Locks the CTA while the ad/sweep is in flight so it cannot be tapped again. */
    private fun lockAction() = with(binding.cleanerActionVw) {
        isEnabled = false
        isClickable = false
        setText(R.string.cleaner_working)
    }

    /** The "before" state: what a sweep would free, with the action still to take. */
    private fun renderFound(result: SweepResult) = with(binding) {
        val nothing = result.files == 0
        cleanerLabelVw.setText(if (nothing) R.string.cleaner_all_clear else R.string.cleaner_ready)
        cleanerAmountVw.text = Formatter.formatShortFileSize(this@CleanerActivity, result.bytes)
        cleanerAmountBodyVw.setText(R.string.cleaner_found_body)
        cleanerRecoveredVw.text = Formatter.formatShortFileSize(this@CleanerActivity, result.bytes)
        cleanerItemsVw.text = getString(R.string.cleaner_items, result.files)
        cleanerActionVw.setText(R.string.cleaner_action)
        cleanerActionVw.isEnabled = !nothing
        cleanerActionVw.isClickable = true
    }

    /** The "after" state, shown once the CTA has swept: the button now closes the page. */
    private fun renderCleaned(freed: SweepResult) = with(binding) {
        cleanerLabelVw.setText(R.string.cleaner_all_clear)
        cleanerAmountVw.text = Formatter.formatShortFileSize(this@CleanerActivity, freed.bytes)
        cleanerAmountBodyVw.setText(R.string.cleaner_cleaned_body)
        cleanerRecoveredVw.text = Formatter.formatShortFileSize(this@CleanerActivity, freed.bytes)
        cleanerItemsVw.text = getString(R.string.cleaner_items, 0)
        cleanerActionVw.setText(R.string.cleaner_done)
        cleanerActionVw.isEnabled = true
        cleanerActionVw.isClickable = true
    }

    /** StatFs on filesDir — the volume this app's data actually lives on. */
    private fun renderStorage() = with(binding) {
        runCatching {
            val stat = StatFs(filesDir.absolutePath)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            val used = total - free
            val percent = if (total == 0L) 0 else ((used * 100) / total).toInt().coerceIn(0, 100)
            cleanerStorageBarVw.progress = percent
            cleanerPercentVw.text = getString(R.string.cleaner_percent, percent)
            cleanerStorageTextVw.text = getString(
                R.string.cleaner_storage_used,
                Formatter.formatShortFileSize(this@CleanerActivity, used),
                Formatter.formatShortFileSize(this@CleanerActivity, total),
            )
        }
    }

    /** Bytes and file count of this app's caches. */
    private fun sweepable(): SweepResult {
        var bytes = 0L
        var files = 0
        listOf(cacheDir, externalCacheDir).forEach { root ->
            walk(root) { f ->
                bytes += f.length()
                files++
            }
        }
        return SweepResult(bytes, files)
    }

    private inline fun walk(dir: File?, onFile: (File) -> Unit) {
        if (dir == null || !dir.exists()) return
        dir.walkBottomUp().forEach { if (it.isFile) onFile(it) }
    }

    private data class SweepResult(val bytes: Long, val files: Int)
}
