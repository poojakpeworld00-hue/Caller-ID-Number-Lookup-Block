package io.launcher.home.activities

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.interfaces.RefreshRecyclerViewListener
import org.fossify.commons.views.MyGridLayoutManager
import io.launcher.home.adapters.HiddenIconsLineup
import io.launcher.home.databinding.LnchActivityHiddenIconsBinding
import io.launcher.home.extensions.launcherConfig
import io.launcher.home.helpers.IconShaper
import io.launcher.home.extensions.getDrawableForPackageName
import io.launcher.home.extensions.hiddenIconsDB
import io.launcher.home.models.HiddenIcon

class HiddenIconsPanel : LauncherBasePanel(), RefreshRecyclerViewListener {
    private val binding by viewBinding(LnchActivityHiddenIconsBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        updateIcons()

        setupEdgeToEdge(padBottomSystem = listOf(binding.manageHiddenIconsListUi))
        setupMaterialScrollListener(binding.manageHiddenIconsListUi, binding.manageHiddenIconsAppbarUi)

        val layoutManager = binding.manageHiddenIconsListUi.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = launcherConfig.drawerColumnCount
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.manageHiddenIconsAppbarUi, NavigationIcon.Arrow)
    }

    private fun updateIcons() {
        ensureBackgroundThread {
            val hiddenIcons = hiddenIconsDB.getHiddenIcons().sortedWith(
                compareBy({
                    it.title.normalizeString().lowercase()
                }, {
                    it.packageName
                })
            ).toMutableList() as ArrayList<HiddenIcon>

            val hiddenIconsEmpty = hiddenIcons.isEmpty()
            runOnUiThread {
                binding.manageHiddenIconsPlaceholderUi.beVisibleIf(hiddenIconsEmpty)
            }

            if (hiddenIcons.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_MAIN, null)
                intent.addCategory(Intent.CATEGORY_LAUNCHER)

                val list = packageManager.queryIntentActivities(intent, PackageManager.PERMISSION_GRANTED)
                for (info in list) {
                    val componentInfo = info.activityInfo.applicationInfo
                    val packageName = componentInfo.packageName
                    val activityName = info.activityInfo.name
                    hiddenIcons.firstOrNull { it.getIconIdentifier() == "$packageName/$activityName" }?.apply {
                        drawable = info.loadIcon(packageManager)?.let { IconShaper.shape(it, launcherConfig.legacyIconTray) }
                            ?: getDrawableForPackageName(packageName)
                    }
                }

                hiddenIcons.firstOrNull { it.packageName == applicationContext.packageName }?.apply {
                    drawable = getDrawableForPackageName(packageName)
                }
            }

            val iconsToRemove = hiddenIcons.filter { it.drawable == null }
            if (iconsToRemove.isNotEmpty()) {
                hiddenIconsDB.removeHiddenIcons(iconsToRemove)
                hiddenIcons.removeAll(iconsToRemove)
            }

            runOnUiThread {
                HiddenIconsLineup(this, hiddenIcons, this, binding.manageHiddenIconsListUi) {
                }.apply {
                    binding.manageHiddenIconsListUi.adapter = this
                }
            }
        }
    }

    override fun refreshItems() {
        updateIcons()
    }
}
