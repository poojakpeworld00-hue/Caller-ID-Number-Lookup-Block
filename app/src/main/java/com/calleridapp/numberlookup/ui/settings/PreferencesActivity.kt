package com.calleridapp.numberlookup.ui.settings

import android.app.role.RoleManager
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import android.view.View
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.calleridapp.admesh.domain.AdsVault
import com.calleridapp.admesh.presentation.AppOpenAdRegistry
import com.calleridapp.admesh.presentation.NativePromo
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.base.HostActivity
import com.calleridapp.numberlookup.data.VaultRegistry
import com.calleridapp.numberlookup.databinding.ActivitySettingsBinding
import com.calleridapp.numberlookup.databinding.ItemPrefCardBinding
import com.calleridapp.numberlookup.databinding.ItemSettingRowBinding
import com.calleridapp.numberlookup.ui.blocklist.BlockRosterActivity
import com.calleridapp.numberlookup.ui.common.CoachMarkFloat
import com.calleridapp.numberlookup.ui.language.LocaleActivity
import com.calleridapp.numberlookup.ui.language.Locales
import com.calleridapp.numberlookup.ui.tools.SimCardActivity
import com.calleridapp.numberlookup.util.AppVault
import com.calleridapp.numberlookup.util.IdentIdRegistry
import com.calleridapp.numberlookup.util.openActivity
import com.calleridapp.numberlookup.util.openPolicyLink
import com.calleridapp.numberlookup.util.openTermLink
import com.calleridapp.numberlookup.util.rateApp
import com.calleridapp.numberlookup.util.shareApp

class PreferencesActivity : HostActivity<ActivitySettingsBinding>() {

    override val layoutId: Int = R.layout.activity_settings

    /** Theme segment order — must match cardTheme's segLight / segDark / segSystem. */
    private val themeOptions =
        listOf(AppVault.THEME_LIGHT, AppVault.THEME_DARK, AppVault.THEME_SYSTEM)

    /** Guards the switch listener while we set its state programmatically. */
    private var isProgrammatic = false

    private val prefs by lazy { VaultRegistry(this) }

    /** Re-syncs the call-screening switch after the role-request dialog returns. */
    private val screeningLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshCallScreeningCard() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.btnBack.setOnClickListener { goBack() }

        // Native ad at the top of the settings list (bottom adaptive banner auto-loads via HostActivity).
        NativePromo().showMidNative(this, binding.adNativeFrame, binding.adShimmer)

        // Preferences grid — Theme is an inline segmented toggle.
        setupThemeToggle()
        bindCard(
            binding.cardLanguage, R.drawable.ic_language, R.string.settings_language,
            currentLanguageName(), chevron = true
        ) {
            openActivity(LocaleActivity.newIntent(this, standalone = true))
        }
        bindCard(
            binding.cardBlocklist, R.drawable.settings_blocklist, R.string.settings_blocklist,
            getString(R.string.settings_blocklist_sub), chevron = true
        ) {
            openActivity<BlockRosterActivity>()
        }
        bindCard(
            binding.cardSim, R.drawable.ic_sim_card, R.string.settings_sim,
            getString(R.string.settings_sim_sub), chevron = false
        ) { openSimManagement() }

        // Call-screening toggle — backed by the Android 10+ CallScreening role.
        setupCallScreening()

        // Account & support
        // Rate-us row is gated by the `is_rateus` Remote Config flag: true (or
        // unset) → visible, false → gone.
        val showRate = AdsVault.getInstance(this).getBoolean("is_rateus", true)
        binding.rowRate.root.visibility = if (showRate) View.VISIBLE else View.GONE
        if (showRate) {
            bindRow(
                binding.rowRate,
                R.drawable.ic_star,
                R.string.settings_rate,
                R.string.settings_rate_sub
            ) {
                rateApp()
            }
        }
        bindRow(
            binding.rowShare,
            R.drawable.settings_share,
            R.string.settings_share,
            R.string.settings_share_sub
        ) {
            shareApp()
        }

        // Legal
        binding.rowPrivacy.ivIcon.setImageResource(R.drawable.ic_policy)
        binding.rowPrivacy.tvTitle.setText(R.string.settings_privacy)
        binding.rowPrivacy.root.setOnClickListener { openPolicyLink() }
        binding.rowTerms.ivIcon.setImageResource(R.drawable.ic_terms)
        binding.rowTerms.tvTitle.setText(R.string.settings_terms)
        binding.rowTerms.root.setOnClickListener { openTermLink() }

        // First-run coach-mark nudging the user to enable the call-screening toggle.
        maybeShowCallScreeningHint()
    }

    private fun bindCard(
        card: ItemPrefCardBinding,
        @DrawableRes icon: Int,
        @StringRes title: Int,
        sub: String,
        chevron: Boolean,
        onClick: () -> Unit
    ) {
        card.ivIcon.setImageResource(icon)
        card.tvTitle.setText(title)
        card.tvSub.text = sub
        card.ivChevron.visibility = if (chevron) View.VISIBLE else View.GONE
        card.root.setOnClickListener { onClick() }
    }

    private fun bindRow(
        row: ItemSettingRowBinding,
        @DrawableRes icon: Int,
        @StringRes title: Int,
        @StringRes sub: Int,
        onClick: () -> Unit
    ) {
        row.ivIcon.setImageResource(icon)
        row.tvTitle.setText(title)
        row.tvSub.setText(sub)
        row.root.setOnClickListener { onClick() }
    }

    override fun onResume() {
        super.onResume()
        refreshCallScreeningCard()
    }

    // ── Call Screening (Android 10+ CallScreening role) ───────────────────

    /** Wires the switch, hiding the whole card where the role isn't available. */
    private fun setupCallScreening() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            binding.cardCallScreening.visibility = View.GONE
            return
        }
        val rm = getSystemService(RoleManager::class.java)
        if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            binding.cardCallScreening.visibility = View.GONE
            return
        }
        refreshCallScreeningCard()
        binding.switchCallScreening.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammatic) return@setOnCheckedChangeListener
            if (isChecked) requestCallScreening() else openDefaultAppsSettings()
        }
    }

    /**
     * First-run coach-mark: dims the whole Settings screen, spotlights the
     * call-screening card through the scrim, and shows a hint bubble beneath it
     * nudging the user to turn the toggle on. Shown only once (persisted via
     * [VaultRegistry.isCallScreeningHintShown]); a tap anywhere dismisses it.
     *
     * Skipped when the card is hidden (role unavailable / pre-Android 10) or the
     * toggle is already on.
     */
    private fun maybeShowCallScreeningHint() {
        if (prefs.isCallScreeningHintShown) return
        if (binding.cardCallScreening.visibility != View.VISIBLE) return
        if (binding.switchCallScreening.isChecked) return

        val card = binding.cardCallScreening
        // Wait for layout (native ad above can shift positions), scroll the card
        // fully into view, then spotlight it on the next frame.
        binding.settingsScroll.post {
            if (isFinishing || isDestroyed) return@post
            val pad = (24 * resources.displayMetrics.density).toInt()
            binding.settingsScroll.scrollTo(0, (card.top - pad).coerceAtLeast(0))
            card.post {
                if (isFinishing || isDestroyed) return@post
                if (binding.switchCallScreening.isChecked) return@post
                prefs.isCallScreeningHintShown = true
                CoachMarkFloat.show(this, card, R.layout.view_call_screening_hint)
            }
        }
    }

    /**
     * Syncs the CallScreening card to the current role state: the switch mirrors
     * whether the role is held, and — once Caller ID is enabled — the whole card
     * is hidden (nothing left to manage). It shows only while Caller ID is still
     * off, and stays hidden where the role isn't available at all.
     */
    private fun refreshCallScreeningCard() {
        if (!IdentIdRegistry.isRoleAvailable(this)) {
            binding.cardCallScreening.visibility = View.GONE
            return
        }
        val enabled = IdentIdRegistry.isCallerIdEnabled(this)
        binding.cardCallScreening.visibility = if (enabled) View.GONE else View.VISIBLE
        isProgrammatic = true
        binding.switchCallScreening.isChecked = enabled
        isProgrammatic = false
    }

    /** Launches the system role-request dialog for call screening. */
    private fun requestCallScreening() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val rm = getSystemService(RoleManager::class.java) ?: return
        if (!rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) return
        if (rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            refreshCallScreeningCard(); return
        }
        AppOpenAdRegistry.skipNextAppOpenAd = true
        screeningLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
    }

    /** The role can't be revoked in-app — send the user to default-apps settings. */
    private fun openDefaultAppsSettings() {
        AppOpenAdRegistry.skipNextAppOpenAd = true
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    private fun currentLanguageName(): String =
        Locales.all.firstOrNull { it.tag == AppVault.selectedLanguage(this) }?.nativeName
            ?: Locales.all.first().nativeName

    /** Opens the system mobile-network screen, falling back to our in-app SIM info. */
    private fun openSimManagement() {
        val opened = runCatching {
            startActivity(Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS))
        }.isSuccess
        if (!opened) openActivity<SimCardActivity>()
    }


    /** Inline Light / Dark / System segmented toggle inside the Theme card. */
    private fun setupThemeToggle() {
        val card = binding.cardTheme
        val cells =
            listOf(card.segLight, card.segDark, card.segSystem) // matches themeOptions order
        val current = AppVault.selectedTheme(this).ifEmpty { AppVault.THEME_LIGHT }
        highlightTheme(cells, themeOptions.indexOf(current).coerceAtLeast(0))

        cells.forEachIndexed { index, cell ->
            cell.setOnClickListener {
                highlightTheme(cells, index)
                val theme = themeOptions[index]
                if (theme != AppVault.selectedTheme(this)) {
                    AppVault.setTheme(
                        this,
                        theme
                    )              // store HostActivity.applyTheme reads
                    AppCompatDelegate.setDefaultNightMode(nightModeFor(theme)) // recreates activities
                }
            }
        }
    }

    /** Maps an AppVault theme string to its AppCompat night-mode constant. */
    private fun nightModeFor(theme: String): Int = when (theme) {
        AppVault.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        AppVault.THEME_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        else -> AppCompatDelegate.MODE_NIGHT_NO
    }

    private fun highlightTheme(cells: List<ImageView>, selected: Int) {
        cells.forEachIndexed { i, cell ->
            val active = i == selected
            cell.setBackgroundResource(if (active) R.drawable.bg_theme_selected else 0)
            val color = ContextCompat.getColor(
                this, if (active) R.color.white else R.color.on_surface_variant
            )
            cell.imageTintList = ColorStateList.valueOf(color)
        }
    }
}
