package com.calleridapp.numberlookup.ui.pkgresult

import android.Manifest
import android.animation.ObjectAnimator
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.calleridapp.admesh.domain.LauncherPlacementAds
import com.calleridapp.admesh.domain.LauncherAdsConfig
import com.calleridapp.admesh.presentation.NativePromo
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.databinding.ScreenPackageResultBinding
import com.calleridapp.numberlookup.base.HostActivity
import com.calleridapp.numberlookup.util.SystemDialogHelper

/**
 * The screen shown when another app is installed or removed (launched by [PackageEventWatcher]).
 *
 *  - **Install → privacy check.** Reads the new app's requested permissions, groups the sensitive
 *    ones (location, camera, microphone, contacts, SMS, …) and shows which are already allowed,
 *    with a shortcut into the app's permission settings.
 *  - **Uninstall → system optimizing.** Confirms the app is gone, then shows the storage and
 *    memory the phone has available now.
 *
 * A short stepped progress plays first, but everything the results show is read from the device —
 * nothing is invented or claimed to have been done that was not. A removed package is already gone,
 * so its label and icon come from [PackageMetadataCache].
 */
class PackageResultActivity : HostActivity<ScreenPackageResultBinding>() {

    override val layoutId: Int = R.layout.screen_package_result

    private val installed: Boolean by lazy { intent.getBooleanExtra(EXTRA_INSTALLED, true) }
    private val affectedPackage: String by lazy { intent.getStringExtra(EXTRA_PACKAGE).orEmpty() }

    /** For a removed app the package is gone, so its label/icon come from the cache. */
    private val cachedMeta by lazy {
        if (installed) null else PackageMetadataCache.read(this, affectedPackage)
    }

    private val handler = Handler(Looper.getMainLooper())

    private var closed = false

    override fun initView() {
        // Recents closes it: an install/uninstall screen is not something to come back to.
        lifecycle.addObserver(SystemDialogHelper(this) { finishAndRemoveTask() })
        // Back is this screen's close (and its install/uninstall close ad) - not the app-wide back
        // interstitial followed by that close ad, which was two full-screen ads for one press.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close()
        })
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyInsets()
        bindHeader()
        wireButtons()
        fillAd()
        playEntrance()
        runCheck()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.pkgresContentVw) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.pkgresHeroVw.updatePadding(top = bars.top)
            (binding.pkgresActionsVw.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)
                ?.let {
                    it.bottomMargin = dp(16) + bars.bottom
                    binding.pkgresActionsVw.layoutParams = it
                }
            insets
        }
        ViewCompat.requestApplyInsets(binding.pkgresContentVw)
    }

    private fun bindHeader() = with(binding) {
        pkgresHeroVw.setBackgroundResource(R.drawable.form_pkgres_hero_installed)
        pkgresBadgeVw.setBackgroundResource(R.drawable.form_pkgres_badge_installed)
        pkgresBadgeIconVw.setImageResource(if (installed) R.drawable.glyph_shield_check else R.drawable.glyph_check)
        pkgresStatusVw.apply {
            setBackgroundResource(R.drawable.form_pkgres_status_installed)
            setText(if (installed) R.string.pkgres_privacy_status else R.string.pkgres_optimize_status)
            setTextColor(getColor(R.color.pkgres_installed))
        }
        pkgresTitleVw.text = getString(
            if (installed) R.string.pkgres_privacy_title else R.string.pkgres_optimize_title,
            resolveLabel(),
        )
        pkgresSubtitleVw.text = ""

        // Icon: from the PackageManager for an installed app, from the pre-uninstall cache for a
        // removed one (the package is gone). Empty tile only if neither has it.
        val icon = if (installed) {
            runCatching { packageManager.getApplicationIcon(affectedPackage) }.getOrNull()
        } else {
            cachedMeta?.iconFile
                ?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
                ?.let { BitmapDrawable(resources, it) }
        }
        pkgresAppIconVw.setImageDrawable(icon)
    }

    private fun resolveLabel(): String {
        if (installed) {
            runCatching {
                val info = packageManager.getApplicationInfo(affectedPackage, 0)
                return packageManager.getApplicationLabel(info).toString()
            }
        }
        return cachedMeta?.label?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra(EXTRA_LABEL)?.takeIf { it.isNotBlank() }
            ?: affectedPackage.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    // ---------------- The check ----------------

    /** The stepped progress, then the results. The steps are paced; the results are real. */
    private fun runCheck() {
        val steps = if (installed) {
            listOf(R.string.pkgres_privacy_step_read, R.string.pkgres_privacy_step_sensitive, R.string.pkgres_privacy_step_allowed)
        } else {
            listOf(R.string.pkgres_optimize_step_removed, R.string.pkgres_optimize_step_storage, R.string.pkgres_optimize_step_memory)
        }
        steps.forEachIndexed { i, step ->
            handler.postDelayed({
                binding.pkgresStepVw.setText(step)
                binding.pkgresProgressBarVw.setProgressCompat((i + 1) * 100 / steps.size, true)
            }, STEP_MS * i)
        }
        handler.postDelayed({ showResults() }, STEP_MS * steps.size)
    }

    private fun showResults() {
        if (isFinishing || isDestroyed) return
        if (installed) showPrivacyResults() else showOptimizeResults()
        binding.pkgresProgressVw.visibility = View.GONE
        // An app asking for nothing sensitive has no rows: the subtitle says so, no empty card.
        if (binding.pkgresChecksVw.childCount > 0) binding.pkgresChecksVw.apply {
            visibility = View.VISIBLE
            alpha = 0f
            animate().alpha(1f).setDuration(260L).start()
        }
        binding.pkgresOpenVw.isEnabled = true
    }

    private fun showPrivacyResults() {
        val groups = sensitiveGroups()
        val allowed = groups.count { it.second }
        binding.pkgresTitleVw.text = getString(R.string.pkgres_privacy_done_title, resolveLabel())
        binding.pkgresSubtitleVw.text = if (groups.isEmpty()) {
            getString(R.string.pkgres_privacy_summary_none)
        } else {
            getString(R.string.pkgres_privacy_summary, groups.size, allowed)
        }
        groups.forEach { (label, isAllowed) ->
            addRow(
                getString(label),
                getString(if (isAllowed) R.string.pkgres_privacy_allowed else R.string.pkgres_privacy_not_allowed),
                if (isAllowed) R.color.pkgres_removed else R.color.pkgres_installed,
            )
        }
    }

    private fun showOptimizeResults() {
        binding.pkgresTitleVw.text = getString(R.string.pkgres_optimize_done_title, resolveLabel())
        binding.pkgresSubtitleVw.setText(R.string.pkgres_optimize_summary)
        addRow(getString(R.string.pkgres_optimize_row_removed), getString(R.string.pkgres_optimize_removed), R.color.pkgres_installed)

        runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            addRow(
                getString(R.string.pkgres_optimize_row_storage),
                getString(R.string.pkgres_of_total, size(stat.availableBytes), size(stat.totalBytes)),
                R.color.pkgres_installed,
            )
        }
        runCatching {
            val info = ActivityManager.MemoryInfo()
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
            addRow(
                getString(R.string.pkgres_optimize_row_memory),
                getString(R.string.pkgres_of_total, size(info.availMem), size(info.totalMem)),
                R.color.pkgres_installed,
            )
        }
    }

    /**
     * The sensitive permission groups the new app asks for, each with whether any permission in it
     * is already granted. Groups the app does not request are left out.
     */
    private fun sensitiveGroups(): List<Pair<Int, Boolean>> {
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    affectedPackage, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(affectedPackage, PackageManager.GET_PERMISSIONS)
            }
        }.getOrNull() ?: return emptyList()

        val requested = info.requestedPermissions ?: return emptyList()
        val granted = requested.filterIndexed { i, _ -> isGranted(info, i) }.toSet()
        return SENSITIVE.mapNotNull { (label, permissions) ->
            val asked = permissions.filter { it in requested }
            if (asked.isEmpty()) null else label to asked.any { it in granted }
        }
    }

    private fun isGranted(info: PackageInfo, index: Int): Boolean {
        val flags = info.requestedPermissionsFlags ?: return false
        return index < flags.size && flags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
    }

    private fun addRow(title: String, value: String, dotColor: Int) {
        val row = LayoutInflater.from(this).inflate(R.layout.row_pkgres_check, binding.pkgresChecksVw, false)
        row.findViewById<TextView>(R.id.pkgresRowTitleVw).text = title
        row.findViewById<TextView>(R.id.pkgresRowValueVw).text = value
        row.findViewById<View>(R.id.pkgresRowDotVw).background.mutate().setTint(getColor(dotColor))
        binding.pkgresChecksVw.addView(row)
    }

    private fun size(bytes: Long): String = Formatter.formatShortFileSize(this, bytes)

    // ---------------- Actions ----------------

    private fun wireButtons() = with(binding) {
        pkgresCloseVw.setOnClickListener { close() }
        pkgresDoneVw.setOnClickListener { close() }
        if (installed) {
            // Review permissions: the new app's own settings page, where each one can be changed.
            pkgresOpenVw.setText(R.string.pkgres_privacy_review)
            pkgresOpenVw.isEnabled = false // until the check has run
            pkgresOpenVw.setOnClickListener { openNewAppSettings() }
        } else {
            // Nothing to review once it is removed — let Done fill the row.
            pkgresOpenVw.visibility = View.GONE
            (pkgresDoneVw.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let {
                it.marginEnd = 0
                pkgresDoneVw.layoutParams = it
            }
        }
    }

    private fun openNewAppSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", affectedPackage, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finishSafely()
    }

    /** The icon settles in and the copy rises — the screen arrives rather than snapping on. */
    private fun playEntrance() = with(binding) {
        pkgresAppIconVw.scaleX = 0.6f
        pkgresAppIconVw.scaleY = 0.6f
        pkgresAppIconVw.animate()
            .scaleX(1f).scaleY(1f)
            .setInterpolator(OvershootInterpolator(2.2f))
            .setDuration(420L)
            .start()
        pkgresBadgeVw.alpha = 0f
        pkgresBadgeVw.animate().alpha(1f).setStartDelay(260L).setDuration(220L).start()
        listOf<View>(pkgresStatusVw, pkgresTitleVw, pkgresSubtitleVw, pkgresBodyVw)
            .forEachIndexed { index, view ->
                view.alpha = 0f
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, dp(12).toFloat(), 0f).apply {
                    startDelay = 90L * index
                    duration = 320L
                    start()
                }
                view.animate().alpha(1f).setStartDelay(90L * index).setDuration(320L).start()
            }
    }

    private fun fillAd() {
        if (!LauncherAdsConfig.packageResultSettings(this).bodyNative) return
        binding.adNativeFrame.visibility = View.VISIBLE
        NativePromo().showMidNative2(this, binding.adNativeFrame, binding.adShimmer)
    }

    private fun close() {
        if (closed) return
        closed = true
        binding.pkgresCloseVw.isEnabled = false
        binding.pkgresDoneVw.isEnabled = false
        binding.pkgresOpenVw.isEnabled = false
        // `install_*` / `uninstall_*` (`ad_flow` or a link chain): a close ad, then the screen goes.
        val placement = if (installed) "install" else "uninstall"
        if (LauncherPlacementAds.hasOwnFlow(this, placement)) {
            LauncherPlacementAds.showInterstitial(this, placement) { finishSafely() }
            return
        }
        finishSafely()
    }

    /** Back behaves as Done, so the screen has one way out. */
    override fun performBack() = close()

    private fun finishSafely() {
        if (!isFinishing) finish()
    }

    private fun dp(value: Int) = Math.round(value * resources.displayMetrics.density)

    companion object {
        const val EXTRA_INSTALLED = "package_installed"
        const val EXTRA_PACKAGE = "package_name"
        const val EXTRA_LABEL = "package_label"
        const val EXTRA_VERSION = "package_version"
        const val EXTRA_CLASS = "package_class"

        /** How long each progress step shows; three steps, so the check reads for about 2 s. */
        private const val STEP_MS = 700L

        /** The permission groups a user would care about, as Settings names them. */
        private val SENSITIVE: List<Pair<Int, List<String>>> = listOf(
            R.string.pkgres_perm_location to listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                "android.permission.ACCESS_BACKGROUND_LOCATION",
            ),
            R.string.pkgres_perm_camera to listOf(Manifest.permission.CAMERA),
            R.string.pkgres_perm_microphone to listOf(Manifest.permission.RECORD_AUDIO),
            R.string.pkgres_perm_contacts to listOf(
                Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.GET_ACCOUNTS,
            ),
            R.string.pkgres_perm_sms to listOf(
                Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
            ),
            R.string.pkgres_perm_call_log to listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG),
            R.string.pkgres_perm_phone to listOf(
                Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE, "android.permission.READ_PHONE_NUMBERS",
            ),
            R.string.pkgres_perm_media to listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VIDEO",
                "android.permission.READ_MEDIA_AUDIO",
                "android.permission.MANAGE_EXTERNAL_STORAGE",
            ),
            R.string.pkgres_perm_calendar to listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            R.string.pkgres_perm_sensors to listOf(Manifest.permission.BODY_SENSORS),
            R.string.pkgres_perm_nearby to listOf(
                "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT", "android.permission.NEARBY_WIFI_DEVICES",
            ),
            R.string.pkgres_perm_notifications to listOf("android.permission.POST_NOTIFICATIONS"),
        )
    }
}
