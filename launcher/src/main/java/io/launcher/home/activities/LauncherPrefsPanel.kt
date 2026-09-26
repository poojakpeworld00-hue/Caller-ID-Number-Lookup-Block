package io.launcher.home.activities

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.launchMoreAppsFromUsIntent
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.isTiramisuPlus
import org.fossify.commons.models.FAQItem
import org.fossify.commons.models.RadioItem
import io.launcher.home.R
import io.launcher.home.databinding.LnchActivitySettingsBinding
import io.launcher.home.profile.DrawerMode
import io.launcher.home.extensions.launcherConfig
import io.launcher.home.helpers.MAX_COLUMN_COUNT
import io.launcher.home.helpers.MAX_ROW_COUNT
import io.launcher.home.helpers.MIN_COLUMN_COUNT
import io.launcher.home.helpers.MIN_ROW_COUNT
import io.launcher.home.receivers.LockDeviceAdminReceiver
import java.util.Locale
import kotlin.system.exitProcess

private const val DRAWER_STYLE_VERTICAL = 0
private const val DRAWER_STYLE_PAGED = 1

class LauncherPrefsPanel : LauncherBasePanel() {

    private val binding by viewBinding(LnchActivitySettingsBinding::inflate)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupEdgeToEdge(padBottomSystem = listOf(binding.settingsNestedScrollviewUi))
        setupMaterialScrollListener(binding.settingsNestedScrollviewUi, binding.settingsAppbarUi)
        setupOptionsMenu()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.settingsAppbarUi, NavigationIcon.Arrow)
        refreshMenuItems()

        setupCustomizeColors()
        setupUseEnglish()
        setupDoubleTapToLock()
        setupAppDrawerEnabled()
        setupDrawerStyle()
        setupCloseAppDrawerOnOtherAppOpen()
        setupOpenKeyboardOnAppDrawer()
        setupDrawerColumnCount()
        setupDrawerSearchBar()
        setupShowDrawerAppLabels()
        setupHomeRowCount()
        setupHomeColumnCount()
        setupShowHomeAppLabels()
        setupLanguage()
        setupManageHiddenIcons()
        updateTextColors(binding.settingsHolderUi)

        arrayOf(
            binding.settingsColorCustomizationSectionLabelUi,
            binding.settingsGeneralSettingsLabelUi,
            binding.settingsDrawerSettingsLabelUi,
            binding.settingsHomeScreenLabelUi
        ).forEach {
            it.setTextColor(getProperPrimaryColor())
        }
    }

    private fun setupOptionsMenu() {
        binding.settingsToolbarUi.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.aboutUi -> launchAbout()
                R.id.more_apps_from_usUi -> launchMoreAppsFromUsIntent()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun refreshMenuItems() {
        binding.settingsToolbarUi.menu.apply {
            findItem(R.id.more_apps_from_usUi).isVisible =
                !resources.getBoolean(org.fossify.commons.R.bool.hide_google_relations)
        }
    }

    private fun setupCustomizeColors() {
        binding.settingsColorCustomizationHolderUi.setOnClickListener {
            // see LauncherBasePanel.withFossifyPackageNameSpoofed — startCustomizationActivity()
            // carries its own copy of the anti-rebrand check, separate from the one in onCreate()
            withFossifyPackageNameSpoofed { startCustomizationActivity() }
        }
    }

    private fun setupUseEnglish() {
        binding.settingsUseEnglishHolderUi.beVisibleIf(
            beVisible = (launcherConfig.wasUseEnglishToggled || Locale.getDefault().language != "en")
                    && !isTiramisuPlus()
        )

        binding.settingsUseEnglishUi.isChecked = launcherConfig.useEnglish
        binding.settingsUseEnglishHolderUi.setOnClickListener {
            binding.settingsUseEnglishUi.toggle()
            launcherConfig.useEnglish = binding.settingsUseEnglishUi.isChecked
            exitProcess(0)
        }
    }

    private fun setupDoubleTapToLock() {
        val devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        binding.settingsDoubleTapToLockUi.isChecked = devicePolicyManager.isAdminActive(
            ComponentName(this, LockDeviceAdminReceiver::class.java)
        )

        binding.settingsDoubleTapToLockHolderUi.setOnClickListener {
            val isLockDeviceAdminActive = devicePolicyManager.isAdminActive(
                ComponentName(this, LockDeviceAdminReceiver::class.java)
            )
            if (isLockDeviceAdminActive) {
                devicePolicyManager.removeActiveAdmin(
                    ComponentName(this, LockDeviceAdminReceiver::class.java)
                )
            } else {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                intent.putExtra(
                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(this, LockDeviceAdminReceiver::class.java)
                )
                intent.putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(R.string.launcher_lock_device_admin_hint)
                )
                startActivity(intent)
            }
        }
    }

    /**
     * The drawer on/off switch. Off is the classic no-drawer home: LauncherPanel puts every app on
     * a page the next time it resumes and stops answering the swipe-up; the drawer's own rows are
     * hidden here because nothing reads them. Icons already on the pages are left where they are
     * when the drawer is turned back on.
     */
    private fun setupAppDrawerEnabled() {
        fun refresh() {
            val enabled = launcherConfig.isDrawerEnabled
            binding.settingsAppDrawerEnabledUi.isChecked = enabled
            binding.settingsAppDrawerDisabledHintUi.beVisibleIf(!enabled)
            binding.settingsDrawerStyleHolderUi.beVisibleIf(enabled)
            binding.settingsDrawerColumnCountHolderUi.beVisibleIf(enabled)
            binding.settingsDrawerSearchHolderUi.beVisibleIf(enabled)
            binding.settingsOpenKeyboardOnAppDrawerHolderUi.beVisibleIf(enabled && launcherConfig.showSearchBar)
            binding.settingsCloseAppDrawerOnOtherAppHolderUi.beVisibleIf(enabled)
            binding.settingsShowDrawerAppLabelsHolderUi.beVisibleIf(enabled)
        }
        refresh()
        binding.settingsAppDrawerEnabledHolderUi.setOnClickListener {
            // Turning the drawer back on restores the style it had before it went off.
            if (launcherConfig.isDrawerEnabled) {
                launcherConfig.drawerStyleWhenOn = launcherConfig.drawerMode
                launcherConfig.drawerMode = DrawerMode.NONE
            } else {
                launcherConfig.drawerMode = launcherConfig.drawerStyleWhenOn
            }
            refresh()
            setupDrawerStyle()
        }
    }

    private fun setupDrawerStyle() {
        val paged = launcherConfig.drawerMode == DrawerMode.PAGED
        binding.settingsDrawerStyleUi.text = getString(if (paged) R.string.launcher_drawer_style_paged else R.string.launcher_drawer_style_vertical)
        binding.settingsDrawerStyleHolderUi.setOnClickListener {
            val items = arrayListOf(
                RadioItem(DRAWER_STYLE_VERTICAL, getString(R.string.launcher_drawer_style_vertical)),
                RadioItem(DRAWER_STYLE_PAGED, getString(R.string.launcher_drawer_style_paged)),
            )
            val current = if (launcherConfig.drawerMode == DrawerMode.PAGED) DRAWER_STYLE_PAGED else DRAWER_STYLE_VERTICAL
            RadioGroupDialog(this, items, current) {
                launcherConfig.drawerMode = if (it as Int == DRAWER_STYLE_PAGED) DrawerMode.PAGED else DrawerMode.VERTICAL
                setupDrawerStyle()
            }
        }
    }

    private fun setupOpenKeyboardOnAppDrawer() {
        binding.settingsOpenKeyboardOnAppDrawerHolderUi.beVisibleIf(launcherConfig.showSearchBar && launcherConfig.isDrawerEnabled)
        binding.settingsOpenKeyboardOnAppDrawerUi.isChecked = launcherConfig.autoShowKeyboardInAppDrawer
        binding.settingsOpenKeyboardOnAppDrawerHolderUi.setOnClickListener {
            binding.settingsOpenKeyboardOnAppDrawerUi.toggle()
            launcherConfig.autoShowKeyboardInAppDrawer = binding.settingsOpenKeyboardOnAppDrawerUi.isChecked
        }
    }

    private fun setupCloseAppDrawerOnOtherAppOpen() {
        binding.settingsCloseAppDrawerOnOtherAppUi.isChecked = launcherConfig.closeAppDrawer
        binding.settingsCloseAppDrawerOnOtherAppHolderUi.setOnClickListener {
            binding.settingsCloseAppDrawerOnOtherAppUi.toggle()
            launcherConfig.closeAppDrawer = binding.settingsCloseAppDrawerOnOtherAppUi.isChecked
        }
    }

    private fun setupDrawerColumnCount() {
        val currentColumnCount = launcherConfig.drawerColumnCount
        binding.settingsDrawerColumnCountUi.text = currentColumnCount.toString()
        binding.settingsDrawerColumnCountHolderUi.setOnClickListener {
            val items = ArrayList<RadioItem>()
            for (i in 1..MAX_COLUMN_COUNT) {
                items.add(
                    RadioItem(
                        id = i,
                        title = resources.getQuantityString(
                            org.fossify.commons.R.plurals.column_counts, i, i
                        )
                    )
                )
            }

            RadioGroupDialog(this, items, currentColumnCount) {
                val newColumnCount = it as Int
                if (currentColumnCount != newColumnCount) {
                    launcherConfig.drawerColumnCount = newColumnCount
                    setupDrawerColumnCount()
                }
            }
        }
    }

    private fun setupDrawerSearchBar() {
        val showSearchBar = launcherConfig.showSearchBar
        binding.settingsShowSearchBarUi.isChecked = showSearchBar
        binding.settingsDrawerSearchHolderUi.setOnClickListener {
            binding.settingsShowSearchBarUi.toggle()
            launcherConfig.showSearchBar = binding.settingsShowSearchBarUi.isChecked
            binding.settingsOpenKeyboardOnAppDrawerHolderUi.beVisibleIf(launcherConfig.showSearchBar && launcherConfig.isDrawerEnabled)
        }
    }

    private fun setupShowDrawerAppLabels() {
        binding.settingsShowDrawerAppLabelsUi.isChecked = launcherConfig.showDrawerAppLabels
        binding.settingsShowDrawerAppLabelsHolderUi.setOnClickListener {
            binding.settingsShowDrawerAppLabelsUi.toggle()
            launcherConfig.showDrawerAppLabels = binding.settingsShowDrawerAppLabelsUi.isChecked
        }
    }

    private fun setupHomeRowCount() {
        val currentRowCount = launcherConfig.homeRowCount
        binding.settingsHomeScreenRowCountUi.text = currentRowCount.toString()
        binding.settingsHomeScreenRowCountHolderUi.setOnClickListener {
            val items = ArrayList<RadioItem>()
            for (i in MIN_ROW_COUNT..MAX_ROW_COUNT) {
                items.add(
                    RadioItem(
                        id = i,
                        title = resources.getQuantityString(
                            org.fossify.commons.R.plurals.row_counts, i, i
                        )
                    )
                )
            }

            RadioGroupDialog(this, items, currentRowCount) {
                val newRowCount = it as Int
                if (currentRowCount != newRowCount) {
                    launcherConfig.homeRowCount = newRowCount
                    setupHomeRowCount()
                }
            }
        }
    }

    private fun setupHomeColumnCount() {
        val currentColumnCount = launcherConfig.homeColumnCount
        binding.settingsHomeScreenColumnCountUi.text = currentColumnCount.toString()
        binding.settingsHomeScreenColumnCountHolderUi.setOnClickListener {
            val items = ArrayList<RadioItem>()
            for (i in MIN_COLUMN_COUNT..MAX_COLUMN_COUNT) {
                items.add(
                    RadioItem(
                        id = i,
                        title = resources.getQuantityString(
                            org.fossify.commons.R.plurals.column_counts, i, i
                        )
                    )
                )
            }

            RadioGroupDialog(this, items, currentColumnCount) {
                val newColumnCount = it as Int
                if (currentColumnCount != newColumnCount) {
                    launcherConfig.homeColumnCount = newColumnCount
                    setupHomeColumnCount()
                }
            }
        }
    }

    private fun setupShowHomeAppLabels() {
        binding.settingsShowHomeAppLabelsUi.isChecked = launcherConfig.showHomeAppLabels
        binding.settingsShowHomeAppLabelsHolderUi.setOnClickListener {
            binding.settingsShowHomeAppLabelsUi.toggle()
            launcherConfig.showHomeAppLabels = binding.settingsShowHomeAppLabelsUi.isChecked
        }
    }

    @SuppressLint("NewApi")
    private fun setupLanguage() {
        binding.settingsLanguageUi.text = Locale.getDefault().displayLanguage
        binding.settingsLanguageHolderUi.beVisibleIf(isTiramisuPlus())
        binding.settingsLanguageHolderUi.setOnClickListener {
            launchChangeAppLanguageIntent()
        }
    }

    private fun setupManageHiddenIcons() {
        binding.settingsManageHiddenIconsHolderUi.setOnClickListener {
            startActivity(Intent(this, HiddenIconsPanel::class.java))
        }
    }

    private fun launchAbout() {
        val licenses = 0L
        val faqItems = ArrayList<FAQItem>()

        if (!resources.getBoolean(org.fossify.commons.R.bool.hide_google_relations)) {
            faqItems.add(
                FAQItem(
                    title = org.fossify.commons.R.string.faq_2_title_commons,
                    text = org.fossify.commons.R.string.faq_2_text_commons
                )
            )
            faqItems.add(
                FAQItem(
                    title = org.fossify.commons.R.string.faq_6_title_commons,
                    text = org.fossify.commons.R.string.faq_6_text_commons
                )
            )
        }

        startAboutActivity(
            appNameId = R.string.launcher_app_launcher_name,
            licenseMask = licenses,
            versionName = hostVersionName(),
            faqItems = faqItems,
            showFAQBeforeMail = true
        )
    }

    /**
     * The host app version, shown on the About screen.
     *
     * Read off the package manager rather than a BuildConfig constant: the module has its own
     * BuildConfig, and its version is the launcher library version, which is not what a user
     * looking at "About" is asking for.
     */
    private fun hostVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")
}
