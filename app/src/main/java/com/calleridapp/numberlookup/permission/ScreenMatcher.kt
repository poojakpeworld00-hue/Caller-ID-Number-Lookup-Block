package com.calleridapp.numberlookup.permission

/**
 * Decides which [AccessRule]s apply to a given Activity.
 *
 * Matching is done on the Activity's *simple class name*, case-insensitively,
 * so Remote Config stays readable and refactors that only move a class between
 * packages don't break targeting.
 *
 * [LEGACY_NAMES] maps the pre-rename class names to the current ones, so
 * Remote Config documents written against the old build keep matching. Drop an
 * entry once the server-side rule has been updated.
 */
object ScreenMatcher {

    private val LEGACY_NAMES: Map<String, String> = mapOf(
        "ADHomeActivity" to "ADDashboardActivity",
        "BaseActivity" to "HostActivity",
        "BatteryActivity" to "PowerGaugeActivity",
        "BlocklistActivity" to "BlockRosterActivity",
        "CallDetailActivity" to "CallInsightActivity",
        "CompassActivity" to "BearingActivity",
        "CountryPickerActivity" to "TerritoryPickerActivity",
        "DialerActivity" to "KeypadActivity",
        "FlashlightActivity" to "TorchActivity",
        "FsiPermissionActivity" to "FullScreenAccessActivity",
        "IncomingCallActivity" to "InboundCallActivity",
        "LanguageActivity" to "LocaleActivity",
        "LevelActivity" to "TiltActivity",
        "LightMeterActivity" to "LuxMeterActivity",
        "LookupDetailActivity" to "IdentifyInsightActivity",
        "LookupHistoryActivity" to "IdentifyTraceActivity",
        "MainActivity" to "ShellActivity",
        "OnboardingActivity" to "IntroActivity",
        "OverlayPermissionActivity" to "FloatAccessActivity",
        "SettingsActivity" to "PreferencesActivity",
        "SimInfoActivity" to "SimCardActivity",
        "SoundMeterActivity" to "DecibelActivity",
        "SpeedometerActivity" to "VelocityActivity",
        "SplashActivity" to "LaunchActivity",
        "StopwatchActivity" to "ChronoActivity",
        "TermsActivity" to "ConsentActivity",
        "TimerActivity" to "CountdownActivity",
        "ToolsActivity" to "UtilityActivity",
    )

    /**
     * Returns the enabled rules that target [activitySimpleName], preserving
     * the caller's ordering (the queue applies priority afterwards).
     */
    fun rulesFor(
        activitySimpleName: String,
        allRules: List<AccessRule>,
    ): List<AccessRule> = allRules.filter { rule ->
        rule.enabled && rule.activities.any { named ->
            named.equals(activitySimpleName, ignoreCase = true) ||
                LEGACY_NAMES[named]?.equals(activitySimpleName, ignoreCase = true) == true
        }
    }
}
