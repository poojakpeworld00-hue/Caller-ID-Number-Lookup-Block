# Remote Config: `GET_DATA_LIST_1` / `DEBUG_GET_DATA_LIST_1`

The ad flow ported from the reference app (Caller__ID__Lookup, v_1.0.4) reads its whole
configuration from these two parameters. Release builds read `GET_DATA_LIST_1`, debug builds
`DEBUG_GET_DATA_LIST_1` (`ConfigSync.blobKey` is the only place the name lives).

The old `GET_DATA_LIST` / `DEBUG_GET_DATA_LIST` are left alone on purpose: builds already in users'
hands keep reading them, and they do not understand the placement keys below.

| File | Parameter | Type |
|---|---|---|
| `GET_DATA_LIST_1.json` | `GET_DATA_LIST_1` | JSON |
| `DEBUG_GET_DATA_LIST_1.json` | `DEBUG_GET_DATA_LIST_1` | JSON |

## Contents

Both files are the reference app's published blobs, unchanged except for this app's own legal links
(`PrivacyPolicy`, `TermLink`, both audiences). Ad unit ids are Google **test** ids — replace them with
this app's real units before a release build reads the parameter.

Each file has an `organic` and a `marketing` block. Beyond the keys the old blob had, they carry:

- `placements` — per-placement ad settings (`splash`, `back`, `appOpen`, `drawer`, `leftPanel`,
  `rightPanel`, `onboarding[_<screen>]`, `appExit`, `unlock`, `recent`, `install`, `uninstall`,
  `charge`, `discharge`): `ads_on`, `ad_flow`, `link_first_then`, `DirectLink`, units, … — read by
  `LauncherPlacementAds`. A placement switches away from its fixed ad only when it has its **own**
  `ad_flow` or link chain.
- `link_first_then`, `link_first_show_all`, `link_open_in`, `inter_fallback`,
  `RewardedAds` — the global link / fallback vocabulary.
- `launcher_config` — the launcher module (gesture ads, `app_exit`, `unlock_ads`, sponsored drawer
  tiles, `dock_host_app`, guide).
- `launcher_ads` — onboarding order and per-screen ads, drawer flows (`app_drawer`), `recent_ad`,
  `system_ads.charge` / `discharge`, `package_result`.
- `onboarding_home` (`"app"` → the app's own home after onboarding), `recent_playstore`,
  `recent_playstore_window_sec`.

### Native ad theme — one palette per audience

`NativeTheme` sits in each audience block and holds **only that audience's** colours:

```json
"organic":   { "NativeTheme": { "NativeLight": { "btnColor": "…", "btnText": "…", "bgColor": "…", "textColor": "…" }, "NativeDark": { … } } },
"marketing": { "NativeTheme": { "NativeLight": { … }, "NativeDark": { … } } }
```

Change the organic palette in `organic`, the marketing palette in `marketing`. There is no
`marketing` / `default` wrapper any more (the old shape repeated both palettes in both
audiences). `ConfigSync` stores the audience's palette and `applyNativeAdTheme` copies the
light or dark half into `NativeBgColor` / `NativetxtColor` / `NativebtnColor` /
`NativebtntxtColor`. A cached config in the old shape is still understood.

### Onboarding ads — `launcher_ads.onboarding`

`order` lists the flow (`set_default`, `welcome`, `language`, `intro`). Each screen, plus `fsi`
(the full-screen-intent permission screen, shown after language), has one block:

```json
"welcome": {
  "ads_on": true,                 // one switch for every ad on the screen
  "skip_enabled": true,           // false → no Skip; Continue is the only way on
  "slot": { "enabled": true, "ad_type": "native", "native_type": "mid" },   // the bottom ad
  "exit_ad": {                    // what shows when the user leaves the screen
    "enabled": true,
    "counter": 0,                 // exits skipped before one shows; 0 = every exit
    "mode": "one",                // one | all | sequence
    "sequence": ["inter", "directlink", "appopen", "fullscreen_native", "rewarded"]
  }
}
```

- `mode: one` — the first ready format in `sequence` shows, every exit.
- `mode: sequence` — one ad per exit, each exit starting after the format shown last.
- `mode: all` — every ready format back to back, then the screen moves on.
- A format with no unit id of its own uses the global one (`googleInter`, `googleAppopen`,
  `googleRewarded`, `googleNative`, `DirectLink`). `directlink` takes `exit_ad.ads.directlink.urls`.
  `full_native`, `app_open`, `reward` and `link` are accepted spellings.
- `language` has no `skip_enabled`: its only control is Done.

`onboarding.splash` — the splash ad's format and pacing (its on/off is `is_splash_ads`):

| key | values |
|---|---|
| `ad_type` | `appopen` \| `inter` (empty → the `is_splash_inter_show` switch) |
| `show_mode` | `always` \| `once` (first launch ever) \| `after_launches` |
| `launches` | for `after_launches`: no ad on the first N launches, then every launch |

### Launcher ads — `placements`

Every launcher ad moment is a placement, and `placements.<name>` says what shows there:

```json
"drawer": {
  "ads_on": true,
  "ad_flow": ["inter", "directlink", "appopen", "fullnative", "rewarded"],
  "ad_flow_mode": "one",      // one | all | sequence
  "counter": 0                // shows skipped between ads (see the table for who counts)
}
```

- `ad_flow` is the chain, in any order (`directlink` takes `DirectLink`, one URL or an array).
  `ad_flow_mode`: `one` = the first ready format, every time; `all` = every ready format back to
  back; `sequence` = one per show, each starting after the one shown last.
- A placement without `ad_flow` keeps its default chain (its interstitial, then `inter_fallback`).
  `link_first_then` + `DirectLink` instead opens the links first and then runs the follow-ups.

| Moment | Placement | On/off | Counter |
|---|---|---|---|
| Left / right panel swipe | `leftPanel`, `rightPanel` | `ads_on` + `launcher_config.gestures.left_swipe/right_swipe.inter_enabled` | `gestures.*.inter_counter` |
| App click (drawer, home) | `drawer.app_click` | `drawer.ads_on` + `gestures.app_launch.inter_enabled` | `gestures.app_launch.inter_counter` |
| App close (back from an app) | `drawer.app_close` | `drawer.ads_on` + `gestures.app_exit.inter_enabled` | `gestures.app_exit.inter_counter` |
| Nav bar Home / Back | `home`, `back` | `ads_on` + `launcher_ads.system_buttons.<btn>.enabled` | `system_buttons.<btn>.ads_counter` |

Nav bar buttons (`home`, `back`, `recents`) share one shape in `launcher_ads.system_buttons.<btn>`: `enabled`, `ads_counter` (presses skipped between ads), `min_gap_sec`, and for Home / Back `ad: { mode: one|all|sequence, sequence: [inter, directlink, appopen, fullscreen_native, rewarded] }`. Recents shows its ads on the page it opens: `recent_ad.close_ad` takes the same `{ enabled, counter, mode, sequence }` object as an onboarding `exit_ad`.

| Nav bar Recents | `recent` | `ads_on` + `recent_ad.enabled` | `placements.recent.counter` |
| Install / uninstall page | `install`, `uninstall` | `ads_on` + `launcher_ads.package_result.enabled` | `placements.<name>.counter` |
| Plug / unplug page | `charge`, `discharge` | `ads_on` + `launcher_ads.system_ads.<name>.enabled` | `placements.<name>.counter` |
| After unlock | `unlock` | `ads_on` + `launcher_config.unlock_ads.enabled` | `placements.unlock.counter` |

For the last four rows `counter` paces the ad shown when the page is closed.

### The drawer — one block, both sides

```json
"drawer": {
  "ads_on": true,                                   // both sides
  "bottom_native": { "enabled": true, "ad_type": "native", "native_type": "mid", "position": 1 },
  "app_click": { "ad_flow": ["inter", "fullnative"], "ad_flow_mode": "one" },   // leaving for an app
  "app_close": { "ad_flow": ["appopen"], "ad_flow_mode": "one" }                // back from the app
}
```

`placements.drawer` replaces three blocks: the flat `drawer`, `appExit` and `launcher_ads.app_drawer` (its
return flow and its bottom ad). The older names still read if a config has them.

### Removed duplicates

These were dropped from both files because they duplicated another key or nothing read them:

| Removed | Use instead |
|---|---|
| `launcher_ads.app_click`, `swipe_right`, `swipe_left` | `launcher_config.gestures.{app_launch, right_swipe, left_swipe}` + `placements` |
| `launcher_ads.home_hint` | `launcher_config.guide` |
| `MarketLink` | the `marketing` block's own `DirectLink` |
| `is_share` | — (unread) |
| `DirectLinkType` | `link_open_in` (one setting; it was never being read, now it is) |
| `placements.appExit`, `launcher_ads.app_drawer` | `placements.drawer.app_close`, `placements.drawer.bottom_native` |
| `onboarding.<screen>.inter_enabled`, `ads_counter` | `exit_ad.enabled`, `exit_ad.counter` |
| `placements.onboarding`, `placements.splash` (`ads_on`) | `onboarding.<screen>.ads_on`; splash on/off is `is_splash_ads` |
| `NativeTheme.marketing` / `NativeTheme.default` | each audience's own `NativeTheme` |

## Publishing

1. Firebase console → Remote Config → add `DEBUG_GET_DATA_LIST_1` (JSON), paste
   `DEBUG_GET_DATA_LIST_1.json`, publish; test a debug build.
2. Swap the test ad unit ids for real ones, then add `GET_DATA_LIST_1` from `GET_DATA_LIST_1.json`.
3. Launch twice: the first launch of a fresh install still reads code defaults.

Until a parameter exists the app keeps working on whatever an earlier run cached (or code
defaults): an empty blob no longer holds the splash.

```
adb logcat -s ConfigSync GetDataConfig LauncherPlacementAds DrawerAdRunner LauncherAdsConfig AppOpen
```
