package io.launcher.home.promo

/**
 * The home-screen guide's steps, in the order they are taught.
 *
 * One gesture per step, and exactly one step on screen at a time. What this replaces was a single
 * chevron animation shown once ever, gated on `was_swipe_hint_shown` — a flag spent the moment the
 * overlay appeared, so it taught only the one gesture it happened to be drawn for and never came
 * back.
 *
 * [configKey] is the field name inside `app_config.<audience>.launcher.guide`, so a step can be
 * turned off from the console without a release. The keys are the reference app's — [
 * SWIPE_RIGHT_HOST] is `swipe_right_contacts` there because that gesture reveals its Contacts+
 * page, where ours reveals the inbox. Same convention as [LauncherAdsConfig]'s gesture keys: the
 * console spelling is kept so a value written for either app resolves in both, and the translation
 * happens here rather than at either end.
 */
enum class LauncherGuideStep(val configKey: String) {
    /** Fling right — reveals the messages panel parked off the left edge. */
    SWIPE_RIGHT_HOST("swipe_right_contacts"),

    /** Fling left — reveals the apps/search panel parked off the right edge. */
    SWIPE_LEFT_APPS("swipe_left_apps"),

    /** Fling (or drag) up — raises the app drawer over the workspace. */
    SWIPE_UP_DRAWER("swipe_up_drawer"),
}
