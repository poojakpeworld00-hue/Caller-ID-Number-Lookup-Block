# Firebase Analytics events (closed list)

Logged only through `Analytics` (`app/src/main/java/com/calleridapp/numberlookup/util/Analytics.kt`).

- Names: lowercase `snake_case`, at most 34 characters. A shipped name is never renamed.
- **First session:** until the first run completes (`LauncherFlow.markOnboardingCompleted`), every
  event below is logged as `first_<name>` (e.g. `first_set_default_open`). Installs that were already past
  onboarding when this logger shipped never get the prefix.
- Sent from debug builds too, and echoed to logcat under the tag `Analytics`.
- Adding an event means adding it here first.

```
adb shell setprop debug.firebase.analytics.app com.calleridapp.numberlookup   # DebugView
adb logcat -s Analytics
```

## Onboarding

| Event | Params | Fires when | Where |
|---|---|---|---|
| `set_default_open` | | the "set as default launcher" screen opens | `OnboardingDefaultLauncherActivity` |
| `set_default_cta_click` | | the main button is tapped | same |
| `set_default_skip_click` | | Skip is tapped | same |
| `set_default_granted` | | the Home role is held (once per screen) | same, `onResume` |
| `set_default_declined` | | the user came back without picking us | same, `askOnDecline` |
| `welcome_open` | | Welcome opens | `OnboardingWelcomeActivity` |
| `welcome_continue_click` / `welcome_skip_click` | | the buttons | same |
| `language_done_click` | `lang`, `source` (`flow`/`settings`) | Done on the language picker | `LocaleActivity.onContinue` |
| `intro_next_click` / `intro_skip_click` | | the intro carousel buttons | `IntroActivity` |
| `fsi_perm_bypass` | | the FSI screen is skipped because the permission is already held | `FullScreenAccessActivity` |

Already existing and unchanged: `screen_<activity>` (every `HostActivity`), `onboarding_completed`,
`FSI_Screen_*`, `FSI_Dialog_*`, `Permission_*`.

## Launcher

| Event | Fires when | Where |
|---|---|---|
| `launcher_home_open` | the launcher home starts | `CallerLauncherBridge.onLauncherStart` |
| `launcher_left_swipe` / `launcher_right_swipe` / `launcher_drawer_open` | the gesture is made | launcher `LauncherPromoController.run` → bridge `onEvent` |
| `launcher_app_click` | an app is opened from the launcher | `CallerLauncherBridge.onAppLaunched` |
| `launcher_app_close` | back in the launcher from an app it opened | `AppExitAd.run` |
| `nav_home_press` / `nav_back_press` | Home / Back on the launcher workspace | `SystemButtonAds.run` |
| `nav_recents_press` | the Recents button is pressed while the app is in front | `RecentAdWatcher.armIfAllowed` |
| `recent_playstore_open` | the one-time Play Store home opened from Recents | `RecentAdWatcher.launchPlayStoreFrom` |
| `unlock_ad_show` (`format`) | the unlock ad is shown | `UnlockAdWatcher.show` |
| `charge_page_open` / `discharge_page_open` | the plug / unplug page opens | `ChargeEventWatcher.fire` |
| `app_installed_page_open` / `app_removed_page_open` | the install / uninstall result page opens | `PackageEventWatcher.fire` |

## Ads run by the launcher's ad chains (`DrawerAdRunner`)

`ad_{type}_{phase}` with param `screen` = the placement (`leftPanel`, `drawer`, `onboarding_set_default`,
`recent_flow`, …).

- `type`: `inter`, `appopen`, `rewarded`, `fullscreen_native`, `directlink`, `custom`
- `phase`: `show` (about to show), `close` (dismissed), `failed` (could not show)

Ad lifecycle events of the older ad managers (`google_inter_*`, `appopen_ad_*`, `NativeAds_*`, …) are
unchanged.

## Not covered (no trigger or not asked for)

- `*_back_press` / `*_home_press` on screens: not wired.
- Reminder / Nudge screens: the product does not use them.
- Notification permission `_bypass` on Android 12 and below: not wired.
