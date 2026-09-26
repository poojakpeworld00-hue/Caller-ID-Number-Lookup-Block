package io.launcher.home.helpers

const val WIDGET_LIST_SECTION = 0
const val WIDGET_LIST_ITEMS_HOLDER = 1

const val REPOSITORY_NAME = "Launcher"

// shared prefs
const val WAS_HOME_SCREEN_INIT = "was_home_screen_init"
/** The package last given the dock's messaging slot - see LauncherPanel.syncHostDockItem. */
const val DOCK_HOST_PACKAGE = "dock_sms_package"
/** Set by the uninstall flow's fake uninstall - our own icon never returns to the dock. */
const val SELF_ICON_HIDDEN = "self_icon_hidden"
const val WAS_ONBOARDING_COMPLETED = "was_onboarding_completed"
const val WAS_SEARCH_BAR_PURGED = "was_search_bar_purged"
// One per guide step, keyed by LauncherGuideStep.name. Deliberately a new key rather than a reuse
// of the old `was_swipe_hint_shown`: that flag meant "the combined overlay was displayed", which is
// not the same question, and reusing it would mark the whole new guide done for every existing
// install on upgrade.
const val GUIDE_STEP_DONE_PREFIX = "guide_step_done_"
// When Play's update prompt was last declined, so `remind_after_ms` has something to measure from.
// Deliberately not the `update_snoozed_at` this replaces: that key was written by the custom dialog
// this feature used to show, and a dismissal recorded against *that* prompt must not silence the
// first Play one on an install that has been through both.
const val UPDATE_DECLINED_AT = "update_declined_at"
// Home profile match - see launcher/profile. The device + previous-launcher facts captured before
// the Home role was taken, and the profile decision made from them on the first home-screen build
// ("{}" when none was applied). Both written once per install.
const val HOME_FINGERPRINT_JSON = "home_fingerprint_json"
const val HOME_PROFILE_JSON = "home_profile_json"
// DrawerMode name: "vertical" (swipe-up drawer) or "none" (every app on the workspace).
const val DRAWER_MODE = "drawer_mode"
// Rows per page of the paged (Samsung-style) drawer.
const val DRAWER_ROW_COUNT = "drawer_row_count"
// The style (vertical / paged) the drawer had before it was switched off, restored on switch-on.
const val DRAWER_STYLE_WHEN_ON = "drawer_style_when_on"
// Dock slots, capped at the home column count when drawn.
const val DOCK_COLUMN_COUNT_PREF = "dock_column_count"
// Drawer search bar at the bottom of the sheet (One UI 6.1+) instead of the top.
const val DRAWER_SEARCH_AT_BOTTOM = "drawer_search_at_bottom"
// Icon size relative to what the grid draws by itself; 1.0 = unchanged.
const val ICON_SCALE = "icon_scale"
const val LABEL_TEXT_SP = "label_text_sp"
const val ICON_SIZE_DP = "icon_size_dp"
const val DOCK_ICON_SIZE_DP = "dock_icon_size_dp"
const val DRAWER_ICON_SIZE_DP = "drawer_icon_size_dp"
const val LEGACY_ICON_TRAY = "legacy_icon_tray"
const val HOME_ROW_COUNT = "home_row_count"
const val HOME_COLUMN_COUNT = "home_column_count"
const val DRAWER_COLUMN_COUNT = "drawer_column_count"
const val SHOW_SEARCH_BAR = "show_search_bar"
const val CLOSE_APP_DRAWER = "close_app_drawer"
const val AUTO_SHOW_KEYBOARD_IN_APP_DRAWER = "auto_show_keyboard_in_app_drawer"
const val SHOW_DRAWER_APP_LABELS = "show_drawer_app_labels"
const val SHOW_HOME_APP_LABELS = "show_home_app_labels"
// Whether the first home page has been built, and the newest install it knew about when it was.
const val HOME_SEEDED = "home_seeded"
const val LAST_INSTALL_SEEN = "last_install_seen"
// The pages hold every app beside the drawer - see HomeProfileApplier.
const val HOME_AND_DRAWER = "home_and_drawer"
// Which layout was applied (launcher_config.os_style) and whether a switch still owes a rebuild.
const val LAUNCHER_STYLE = "launcher_style"
const val HOME_REBUILD_PENDING = "home_rebuild_pending"
// Which search widget (launcher_config.search_widget) the first page's row was built for.
const val SEARCH_WIDGET = "search_widget"

// default home screen grid size
const val ROW_COUNT = 6
const val COLUMN_COUNT = 5
// the dock is its own row of this many slots, centred, whatever the grid column count - the
// default; Config.dockColumnCount is what the grid reads
const val DOCK_COLUMN_COUNT = 4
const val DRAWER_ROW_COUNT_DEFAULT = 6
// The most dock slots the grid will draw, whatever a launcher declares.
const val MAX_DOCK_SLOTS = 7
const val MIN_ROW_COUNT = 2
const val MAX_ROW_COUNT = 15
const val MIN_COLUMN_COUNT = 2
const val MAX_COLUMN_COUNT = 15

const val UNINSTALL_APP_REQUEST_CODE = 50
const val REQUEST_CONFIGURE_WIDGET = 51
const val REQUEST_ALLOW_BINDING_WIDGET = 52
const val REQUEST_CREATE_SHORTCUT = 53
const val REQUEST_SET_DEFAULT = 54
const val REQUEST_DEFAULT_SMS = 55

const val ITEM_TYPE_ICON = 0
const val ITEM_TYPE_WIDGET = 1
const val ITEM_TYPE_SHORTCUT = 2
const val ITEM_TYPE_FOLDER = 3

// widgets rendered by ourselves directly on the grid, they are stored as ITEM_TYPE_WIDGET rows
// with these sentinels in the className column, so no schema change is needed
const val PSEUDO_WIDGET_PREFIX = "io.launcher.home.pseudo."
const val PSEUDO_WIDGET_CLOCK = "${PSEUDO_WIDGET_PREFIX}DigitalClock"
const val PSEUDO_WIDGET_SEARCH = "${PSEUDO_WIDGET_PREFIX}SearchBar"
// The first page's own widgets: the time (top left) and our Google bar where Google's cannot go.
const val PSEUDO_WIDGET_TIME = "${PSEUDO_WIDGET_PREFIX}Time"
const val PSEUDO_WIDGET_GOOGLE_SEARCH = "${PSEUDO_WIDGET_PREFIX}GoogleSearch"
// Page 1 carries only those two widgets; app icons start on the page after it.
const val FIRST_APPS_PAGE = 1

// An app installed or removed reaches the launcher this long after the last change in a burst.
const val PACKAGE_REFRESH_DELAY_MS = 300L

const val WIDGET_HOST_ID = 12345
const val MAX_CLICK_DURATION = 150
