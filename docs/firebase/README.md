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
- `link_first_then`, `link_first_show_all`, `link_open_in`, `inter_fallback`, `DirectLinkType`,
  `RewardedAds` — the global link / fallback vocabulary.
- `launcher_config` — the launcher module (gesture ads, `app_exit`, `unlock_ads`, sponsored drawer
  tiles, `dock_host_app`, guide).
- `launcher_ads` — onboarding order and per-screen ads, drawer flows (`app_drawer`), `recent_ad`,
  `system_ads.charge` / `discharge`, `package_result`.
- `onboarding_home` (`"app"` → the app's own home after onboarding), `recent_playstore`,
  `recent_playstore_window_sec`.

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
