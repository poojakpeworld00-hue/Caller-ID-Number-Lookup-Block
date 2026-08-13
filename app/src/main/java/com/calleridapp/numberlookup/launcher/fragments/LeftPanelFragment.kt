package com.calleridapp.numberlookup.launcher.fragments

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AttributeSet
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.core.view.GestureDetectorCompat
import androidx.core.widget.doAfterTextChanged
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.extensions.showKeyboard
import com.calleridapp.admesh.domain.LauncherAdsConfig
import com.calleridapp.admesh.presentation.NativePromo
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.launcher.activities.MainActivity
import com.calleridapp.numberlookup.launcher.adapters.PanelAppsAdapter
import com.calleridapp.numberlookup.databinding.LeftPanelFragmentBinding
import com.calleridapp.numberlookup.launcher.extensions.launchApp
import com.calleridapp.numberlookup.launcher.models.AppLauncher
import com.calleridapp.numberlookup.launcher.models.appLauncherComparator
import kotlin.math.abs

/**
 * Panel sliding in from the side of the home screen, offering app suggestions and a search field.
 */
class LeftPanelFragment(
    context: Context,
    attributeSet: AttributeSet,
) : MyFragment<LeftPanelFragmentBinding>(context, attributeSet) {

    private var launchers = emptyList<AppLauncher>()
    private var resultsCap = COLLAPSED_RESULTS
    private val nativePromo = NativePromo()

    /** `launcher_ads.right_panel.bottom_native`, resolved once with the panel. */
    private var adSlot = LauncherAdsConfig.Slot(
        enabled = false,
        adType = LauncherAdsConfig.SlotAd.NONE,
        nativeType = "mid2",
        bannerType = "adaptive",
        adUnitId = "",
    )

    /** `launcher_ads.right_panel.suggested_banner` — the slot under the suggested grid. */
    private var suggestedSlot = LauncherAdsConfig.Slot(
        enabled = false,
        adType = LauncherAdsConfig.SlotAd.NONE,
        nativeType = "native_banner",
        bannerType = "adaptive",
        adUnitId = "",
    )

    private lateinit var suggestedAdapter: PanelAppsAdapter
    private lateinit var recentAdapter: PanelAppsAdapter
    private lateinit var resultsAdapter: PanelAppsAdapter
    private lateinit var searchInAdapter: PanelAppsAdapter

    // the panel covers the whole screen while open, so MainActivity never sees these events
    private val gestureDetector = GestureDetectorCompat(context, object : SimpleOnGestureListener() {
        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            if (velocityX > 0 && abs(velocityX) > abs(velocityY)) {
                activity?.hideLeftPanel()
                return true
            }

            return false
        }
    })

    override fun setupFragment(activity: MainActivity) {
        this.activity = activity
        this.binding = LeftPanelFragmentBinding.bind(this)

        // Only warm the slot here. Showing it now would be too early: the native renderers
        // draw whatever NativePromo has already preloaded, and at MainActivity.onCreate that
        // is still null — the frame would hide itself and, since the panel is never
        // re-created, never come back. The actual show happens in onPanelShown().
        adSlot = LauncherAdsConfig.rightPanelSlot(activity)
        suggestedSlot = LauncherAdsConfig.rightPanelSuggestedSlot(activity)
        // A banner slot loads on show, so only a native one is worth warming.
        if (adSlot.needsNativePreload || suggestedSlot.needsNativePreload) {
            nativePromo.loadNativeADs(activity)
        }

        suggestedAdapter = PanelAppsAdapter(R.layout.item_panel_grid_app, ::launchLauncher)
        recentAdapter = PanelAppsAdapter(R.layout.item_panel_grid_app, ::launchLauncher)
        resultsAdapter = PanelAppsAdapter(R.layout.item_panel_result, ::launchLauncher)
        searchInAdapter = PanelAppsAdapter(R.layout.item_panel_search_in, ::searchInApp)

        binding.panelSuggestedGrid.adapter = suggestedAdapter
        binding.panelRecentGrid.adapter = recentAdapter
        binding.panelResultsList.adapter = resultsAdapter
        binding.panelSearchInList.adapter = searchInAdapter

        binding.panelSearch.doAfterTextChanged {
            resultsCap = COLLAPSED_RESULTS
            updateSections()
        }

        binding.panelSearchClear.setOnClickListener {
            binding.panelSearch.setText("")
        }

        binding.panelSeeMore.setOnClickListener {
            resultsCap = if (resultsCap == COLLAPSED_RESULTS) {
                EXPANDED_RESULTS
            } else {
                COLLAPSED_RESULTS
            }
            updateSections()
        }

        binding.panelSearch.setOnEditorActionListener { _, actionId, _ ->
            when (actionId) {
                EditorInfo.IME_ACTION_DONE,
                EditorInfo.IME_ACTION_SEARCH,
                EditorInfo.IME_ACTION_GO -> launchFirstResult()

                else -> false
            }
        }
    }

    /**
     * Called every time the panel slides in. The native renderers draw whatever NativePromo
     * has preloaded and then queue the next one, so asking on each open keeps the slot fresh —
     * and gives it a second chance if the very first fling beat the preload.
     */
    fun onPanelShown() {
        val activity = activity ?: return
        LauncherAdsConfig.showSlot(activity, adSlot, binding.adNativeFrame, binding.adShimmer)
        LauncherAdsConfig.showSlot(
            activity = activity,
            slot = suggestedSlot,
            container = binding.adSuggestedFrame,
            shimmer = binding.adSuggestedShimmer,
        )
    }

    /** Called when the panel is opened from the search pill rather than by a fling. */
    fun focusSearch() {
        binding.panelSearch.requestFocus()
        activity?.showKeyboard(binding.panelSearch)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // do not swallow the event, the lists still have to scroll
        gestureDetector.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    fun gotLaunchers(appLaunchers: List<AppLauncher>) {
        launchers = appLaunchers.sortedWith(appLauncherComparator)
        activity?.runOnUiThread {
            updateSections()
        }
    }

    fun hasQuery() = getQuery().isNotEmpty()

    fun resetSearch() {
        binding.panelSearch.setText("")
        binding.panelSearch.clearFocus()
        binding.panelScroll.scrollTo(0, 0)
    }

    private fun getQuery() = binding.panelSearch.text.toString().trim()

    private fun matchingLaunchers(query: String) = launchers.filter {
        it.title.normalizeString().contains(query.normalizeString(), ignoreCase = true)
    }

    /** Enter on the keyboard opens the top hit, the way the app drawer's search does. */
    private fun launchFirstResult(): Boolean {
        val query = getQuery()
        if (query.isEmpty()) {
            return false
        }

        val first = matchingLaunchers(query).firstOrNull() ?: return false
        launchLauncher(first)
        return true
    }

    private fun updateSections() {
        val query = getQuery()
        val hasQuery = query.isNotEmpty()

        binding.panelSearchClear.beVisibleIf(hasQuery)
        binding.panelSuggestedHeader.beVisibleIf(!hasQuery)
        binding.panelSuggestedGrid.beVisibleIf(!hasQuery)

        if (hasQuery) {
            val results = matchingLaunchers(query)

            binding.panelRecentHeader.beGone()
            binding.panelRecentGrid.beGone()
            binding.panelResultsHeader.beVisibleIf(results.isNotEmpty())
            binding.panelResultsList.beVisibleIf(results.isNotEmpty())
            binding.panelNoResults.beVisibleIf(results.isEmpty())
            binding.panelSeeMore.beVisibleIf(results.size > COLLAPSED_RESULTS)
            binding.panelSeeMore.setText(
                if (resultsCap == COLLAPSED_RESULTS) R.string.see_more else R.string.see_less
            )
            resultsAdapter.submitList(results.take(resultsCap))

            // still offered when no app matched, searching the web for it is the point
            val searchTargets = launchers.filter { it.packageName in SEARCH_IN_PACKAGES }
            binding.panelSearchInHeader.beVisibleIf(searchTargets.isNotEmpty())
            binding.panelSearchInList.beVisibleIf(searchTargets.isNotEmpty())
            searchInAdapter.submitList(searchTargets)
        } else {
            val recent = launchers.drop(SUGGESTED_COUNT).take(RECENT_COUNT)
            binding.panelResultsHeader.beGone()
            binding.panelResultsList.beGone()
            binding.panelNoResults.beGone()
            binding.panelSearchInHeader.beGone()
            binding.panelSearchInList.beGone()
            binding.panelRecentHeader.beVisibleIf(recent.isNotEmpty())
            binding.panelRecentGrid.beVisibleIf(recent.isNotEmpty())

            suggestedAdapter.submitList(launchers.take(SUGGESTED_COUNT))
            recentAdapter.submitList(recent)
        }
    }

    private fun launchLauncher(launcher: AppLauncher) {
        val activity = activity ?: return

        // The app always launches — LauncherAdsConfig.run calls back on every path, including
        // ads off, no fill and no network, so a tap is never swallowed by a missing ad.
        LauncherAdsConfig.run(activity, LauncherAdsConfig.Surface.APP_CLICK) {
            activity.launchApp(launcher.packageName, launcher.activityName)
            activity.hideLeftPanel()
        }
    }

    private fun searchInApp(launcher: AppLauncher) {
        val query = getQuery()
        val uri = when (launcher.packageName) {
            PACKAGE_MAPS -> "geo:0,0?q=${Uri.encode(query)}"
            PACKAGE_PLAY_STORE -> "market://search?q=${Uri.encode(query)}"
            else -> "https://www.google.com/search?q=${Uri.encode(query)}"
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(launcher.packageName)
        }

        try {
            activity?.startActivity(intent)
            activity?.hideLeftPanel()
        } catch (_: ActivityNotFoundException) {
            // the app is installed but cannot handle it, let the system pick a handler
            launchLauncher(launcher)
        }
    }

    companion object {
        private const val SUGGESTED_COUNT = 8
        private const val RECENT_COUNT = 4
        private const val COLLAPSED_RESULTS = 5
        // "See more" reveals every match, hiding hits behind a second cap is just confusing
        private const val EXPANDED_RESULTS = Int.MAX_VALUE

        private const val PACKAGE_CHROME = "com.android.chrome"
        private const val PACKAGE_MAPS = "com.google.android.apps.maps"
        private const val PACKAGE_PLAY_STORE = "com.android.vending"
        private val SEARCH_IN_PACKAGES =
            setOf(PACKAGE_CHROME, PACKAGE_MAPS, PACKAGE_PLAY_STORE)
    }
}
