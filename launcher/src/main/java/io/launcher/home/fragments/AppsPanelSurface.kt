package io.launcher.home.fragments

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AttributeSet
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import androidx.core.view.GestureDetectorCompat
import androidx.core.widget.doAfterTextChanged
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.normalizeString
import io.launcher.home.R
import io.launcher.home.activities.LauncherPanel
import io.launcher.home.adapters.PanelAppsLineup
import io.launcher.home.databinding.LnchLeftPanelFragmentBinding
import io.launcher.home.extensions.launchApp
import io.launcher.home.models.AppLauncher
import io.launcher.home.promo.LauncherAdsConfig
import io.launcher.home.api.LauncherRegistry
import io.launcher.home.models.appLauncherComparator
import kotlin.math.abs

/**
 * Panel sliding in from the side of the home screen, offering app suggestions and a search field.
 */
class AppsPanelSurface(
    context: Context,
    attributeSet: AttributeSet,
) : LauncherSurface<LnchLeftPanelFragmentBinding>(context, attributeSet) {

    private var launchers = emptyList<AppLauncher>()
    private var resultsCap = COLLAPSED_RESULTS

    private lateinit var suggestedAdapter: PanelAppsLineup
    private lateinit var recentAdapter: PanelAppsLineup
    private lateinit var resultsAdapter: PanelAppsLineup
    private lateinit var searchInAdapter: PanelAppsLineup

    // the panel covers the whole screen while open, so LauncherPanel never sees these events
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

    override fun setupFragment(activity: LauncherPanel) {
        this.activity = activity
        this.binding = LnchLeftPanelFragmentBinding.bind(this)

        suggestedAdapter = PanelAppsLineup(R.layout.lnch_item_panel_grid_app, ::launchLauncher)
        recentAdapter = PanelAppsLineup(R.layout.lnch_item_panel_grid_app, ::launchLauncher)
        resultsAdapter = PanelAppsLineup(R.layout.lnch_item_panel_result, ::launchLauncher)
        searchInAdapter = PanelAppsLineup(R.layout.lnch_item_panel_search_in, ::searchInApp)

        binding.panelSuggestedGridUi.adapter = suggestedAdapter
        binding.panelRecentGridUi.adapter = recentAdapter
        binding.panelResultsListUi.adapter = resultsAdapter
        binding.panelSearchInListUi.adapter = searchInAdapter

        binding.panelSearchUi.doAfterTextChanged {
            resultsCap = COLLAPSED_RESULTS
            updateSections()
        }

        binding.panelSearchClearUi.setOnClickListener {
            binding.panelSearchUi.setText("")
        }

        binding.panelSeeMoreUi.setOnClickListener {
            resultsCap = if (resultsCap == COLLAPSED_RESULTS) {
                EXPANDED_RESULTS
            } else {
                COLLAPSED_RESULTS
            }
            updateSections()
        }
    }

    /**
     * Requests both panel slots afresh — the middle one sits between Suggested and Recent, the
     * bottom one is pinned to the panel's foot, outside the scrolling column.
     *
     * Driven by `LauncherPanel.showLeftPanel` on every open rather than once from [setupFragment]:
     * the panel is built at boot and then only parked off screen, so a request made there would
     * leave the same creative on the panel for as long as the launcher process lives — which, on a
     * launcher, is until the device restarts. Asking here also means a user who never swipes the
     * panel open never costs a request.
     *
     * A repeat call *is* the refresh: the SDK empties the frame and dispatches a new request each
     * time, so there is nothing to tear down first — see [io.launcher.home.api.LauncherAds.bindNative].
     *
     * Whether the repeat happens at all is the console's call, per placement — see
     * [LauncherAdsConfig.reloadAdOnOpen]. With it off the first open still fills the slot and every
     * open after that leaves it alone.
     */
    /**
     * Called by the host as the panel starts sliding in: the greeting and date are set for now,
     * then the header, search pill and cards lift in one after another.
     */
    fun onOpened() {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        binding.panelGreetingUi.setText(
            when {
                hour < 12 -> R.string.launcher_greeting_morning
                hour < 17 -> R.string.launcher_greeting_afternoon
                else -> R.string.launcher_greeting_evening
            }
        )
        val locale = java.util.Locale.getDefault()
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM")
        binding.panelDateUi.text = java.text.SimpleDateFormat(pattern, locale).format(java.util.Date())
        io.launcher.home.motion.OnboardingMotion.enter(
            null,
            binding.panelHeaderUi,
            binding.panelSearchHolderUi,
            binding.panelSuggestedHeaderUi,
            binding.panelSuggestedGridUi,
            binding.panelRecentHeaderUi,
            binding.panelRecentGridUi,
        )
    }

    fun refreshAds() {
        val activity = activity ?: return
        LauncherRegistry.ads.bindNativeOnOpen(
            activity, LauncherAdsConfig.KEY_PANEL_MIDDLE, binding.panelMidNativeFrameUi
        )
        LauncherRegistry.ads.bindNativeOnOpen(
            activity, LauncherAdsConfig.KEY_PANEL_BOTTOM, binding.panelNativeFrameUi
        )
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
        binding.panelSearchUi.setText("")
        binding.panelScrollUi.scrollTo(0, 0)
    }

    private fun getQuery() = binding.panelSearchUi.text.toString().trim()

    private fun updateSections() {
        val query = getQuery()
        val hasQuery = query.isNotEmpty()

        binding.panelSearchClearUi.beVisibleIf(hasQuery)
        binding.panelSuggestedHeaderUi.beVisibleIf(!hasQuery)
        binding.panelSuggestedGridUi.beVisibleIf(!hasQuery)

        if (hasQuery) {
            val results = launchers.filter {
                it.title.normalizeString().contains(query.normalizeString(), ignoreCase = true)
            }

            binding.panelRecentHeaderUi.beGone()
            binding.panelRecentGridUi.beGone()
            binding.panelResultsHeaderUi.beVisible()
            binding.panelResultsListUi.beVisible()
            binding.panelSeeMoreUi.beVisibleIf(results.size > COLLAPSED_RESULTS)
            binding.panelSeeMoreUi.setText(
                if (resultsCap == COLLAPSED_RESULTS) R.string.launcher_see_more else R.string.launcher_see_less
            )
            resultsAdapter.submitList(results.take(resultsCap))

            val searchTargets = launchers.filter { it.packageName in SEARCH_IN_PACKAGES }
            binding.panelSearchInHeaderUi.beVisibleIf(searchTargets.isNotEmpty())
            binding.panelSearchInListUi.beVisibleIf(searchTargets.isNotEmpty())
            searchInAdapter.submitList(searchTargets)
        } else {
            val recent = launchers.drop(SUGGESTED_COUNT).take(RECENT_COUNT)
            binding.panelResultsHeaderUi.beGone()
            binding.panelResultsListUi.beGone()
            binding.panelSearchInHeaderUi.beGone()
            binding.panelSearchInListUi.beGone()
            binding.panelRecentHeaderUi.beVisibleIf(recent.isNotEmpty())
            binding.panelRecentGridUi.beVisibleIf(recent.isNotEmpty())

            suggestedAdapter.submitList(launchers.take(SUGGESTED_COUNT))
            recentAdapter.submitList(recent)
        }
    }

    private fun launchLauncher(launcher: AppLauncher) {
        if (launcher.packageName == context.applicationContext.packageName) {
            // Our own app: openHostApp closes this panel itself.
            activity?.openHostApp()
            return
        }
        activity?.launchApp(launcher.packageName, launcher.activityName)
        activity?.hideLeftPanel()
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
        private const val COLLAPSED_RESULTS = 4
        private const val EXPANDED_RESULTS = 8

        private const val PACKAGE_CHROME = "com.android.chrome"
        private const val PACKAGE_MAPS = "com.google.android.apps.maps"
        private const val PACKAGE_PLAY_STORE = "com.android.vending"
        private val SEARCH_IN_PACKAGES =
            setOf(PACKAGE_CHROME, PACKAGE_MAPS, PACKAGE_PLAY_STORE)
    }
}
