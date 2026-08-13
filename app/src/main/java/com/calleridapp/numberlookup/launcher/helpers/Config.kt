package com.calleridapp.numberlookup.launcher.helpers

import android.content.Context
import org.fossify.commons.helpers.BaseConfig
import com.calleridapp.numberlookup.R

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
    }

    var wasHomeScreenInit: Boolean
        get() = prefs.getBoolean(WAS_HOME_SCREEN_INIT, false)
        set(wasHomeScreenInit) = prefs.edit().putBoolean(WAS_HOME_SCREEN_INIT, wasHomeScreenInit).apply()

    var wasOnboardingCompleted: Boolean
        get() = prefs.getBoolean(WAS_ONBOARDING_COMPLETED, false)
        set(wasOnboardingCompleted) = prefs.edit()
            .putBoolean(WAS_ONBOARDING_COMPLETED, wasOnboardingCompleted).apply()

    // separate from wasHomeScreenInit so it also backfills installs that already ran the
    // regular first-run seeding before the search pill existed
    var wasSearchBarSeeded: Boolean
        get() = prefs.getBoolean(WAS_SEARCH_BAR_SEEDED, false)
        set(wasSearchBarSeeded) = prefs.edit().putBoolean(WAS_SEARCH_BAR_SEEDED, wasSearchBarSeeded).apply()

    // same idea for the clock, it was added after the search pill
    var wasClockSeeded: Boolean
        get() = prefs.getBoolean(WAS_CLOCK_SEEDED, false)
        set(wasClockSeeded) = prefs.edit().putBoolean(WAS_CLOCK_SEEDED, wasClockSeeded).apply()

    var wasSwipeHintShown: Boolean
        get() = prefs.getBoolean(WAS_SWIPE_HINT_SHOWN, false)
        set(wasSwipeHintShown) = prefs.edit()
            .putBoolean(WAS_SWIPE_HINT_SHOWN, wasSwipeHintShown).apply()

    // How far through `home_hint.swipeHints` the coach mark has got: the hints are taught one
    // at a time, and this index only moves when the user actually performs the one on screen.
    var swipeHintIndex: Int
        get() = prefs.getInt(SWIPE_HINT_INDEX, 0)
        set(swipeHintIndex) = prefs.edit().putInt(SWIPE_HINT_INDEX, swipeHintIndex).apply()

    // How far the RC-ordered first-run sequence has got. Kept in prefs rather than an intent
    // extra because the full-screen-intent screen can sit between two onboarding steps and
    // rebuilds the intent for the one that follows, which would drop an extra.
    var onboardingStep: Int
        get() = prefs.getInt(ONBOARDING_STEP, 0)
        set(onboardingStep) = prefs.edit().putInt(ONBOARDING_STEP, onboardingStep).apply()

    var homeColumnCount: Int
        get() = prefs.getInt(HOME_COLUMN_COUNT, COLUMN_COUNT)
        set(homeColumnCount) = prefs.edit().putInt(HOME_COLUMN_COUNT, homeColumnCount).apply()

    var homeRowCount: Int
        get() = prefs.getInt(HOME_ROW_COUNT, ROW_COUNT)
        set(homeRowCount) = prefs.edit().putInt(HOME_ROW_COUNT, homeRowCount).apply()

    var drawerColumnCount: Int
        get() = prefs.getInt(DRAWER_COLUMN_COUNT, context.resources.getInteger(R.integer.portrait_column_count))
        set(drawerColumnCount) = prefs.edit().putInt(DRAWER_COLUMN_COUNT, drawerColumnCount).apply()

    var showSearchBar: Boolean
        get() = prefs.getBoolean(SHOW_SEARCH_BAR, true)
        set(showSearchBar) = prefs.edit().putBoolean(SHOW_SEARCH_BAR, showSearchBar).apply()

    var closeAppDrawer: Boolean
        get() = prefs.getBoolean(CLOSE_APP_DRAWER, false)
        set(closeAppDrawer) = prefs.edit().putBoolean(CLOSE_APP_DRAWER, closeAppDrawer).apply()

    var autoShowKeyboardInAppDrawer: Boolean
        get() = prefs.getBoolean(AUTO_SHOW_KEYBOARD_IN_APP_DRAWER, false)
        set(autoShowKeyboardInAppDrawer) = prefs.edit()
            .putBoolean(AUTO_SHOW_KEYBOARD_IN_APP_DRAWER, autoShowKeyboardInAppDrawer).apply()

    var showDrawerAppLabels: Boolean
        get() = prefs.getBoolean(SHOW_DRAWER_APP_LABELS, true)
        set(showDrawerAppLabels) = prefs.edit().putBoolean(SHOW_DRAWER_APP_LABELS, showDrawerAppLabels).apply()

    var showHomeAppLabels: Boolean
        get() = prefs.getBoolean(SHOW_HOME_APP_LABELS, true)
        set(showHomeAppLabels) = prefs.edit().putBoolean(SHOW_HOME_APP_LABELS, showHomeAppLabels).apply()
}
