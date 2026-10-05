# `launcher_ads` — Remote Config schema (v2)

One Remote Config string parameter, `launcher_ads`, owning everything the launcher does
around ads, hints and the first-run route. Stored as JSON text in AdsVault by
`ADDashboardActivity.ingestConfig` and read back with `JSONObject`, same as `intro_display`
and `ScreenAds`.

## Paste-ready value

```json
{
  "right_panel": {
    "bottom_native": {
      "enabled": true,
      "ad_type": "native",
      "native_type": "mid2",
      "banner_type": "adaptive",
      "ad_unit_id": ""
    }
  },

  "default_home_screen": {
    "enabled": true,
    "skip_if_default": true,
    "skip_rest_on_grant": true
  },

  "onboarding": {
    "order": ["welcome", "set_default", "intro", "language"],
    "welcome": {
      "inter_enabled": false,
      "ads_counter": 0,
      "slot": { "enabled": true, "ad_type": "native", "native_type": "mid", "banner_type": "adaptive", "ad_unit_id": "" }
    },
    "set_default": {
      "inter_enabled": false,
      "ads_counter": 0,
      "slot": { "enabled": true, "ad_type": "native", "native_type": "mid", "banner_type": "adaptive", "ad_unit_id": "" }
    },
    "intro": {
      "inter_enabled": true,
      "ads_counter": 0,
      "slot": { "enabled": true, "ad_type": "native", "native_type": "big", "banner_type": "adaptive", "ad_unit_id": "" }
    },
    "language": {
      "inter_enabled": true,
      "ads_counter": 0,
      "slot": { "enabled": true, "ad_type": "banner", "native_type": "mid", "banner_type": "collapsible", "ad_unit_id": "" }
    }
  },

  "defaultHome": {
    "app_drawer": { "ad_counter": 1 }
  },

  "notDefaultHome": {
    "right_panel": { "bottom_native": { "ad_type": "banner" } }
  }
}
```

## How `defaultHome` / `notDefaultHome` resolve

Everything outside those two keys is the **base**. Exactly one variant is applied on top of
the base per read, chosen by `isDefaultLauncher()`:

- app holds the HOME role → `defaultHome`
- app does not → `notDefaultHome`

The overlay is a **deep merge, key by key**. A key present in the variant wins; a key absent
inherits from the base — at every level, so `{"app_drawer": {"ad_counter": 1}}` changes only
the counter and leaves the rest of `app_drawer` alone.
`{}` (or a missing variant) means "use the base unchanged". Arrays are replaced whole, not
merged — `sequence` in a variant fully replaces the base list.

So: change one field inside either block and only that field flips. That is the
"any one change inside automatically override" behaviour.

## Key reference

### Not in `launcher_ads` — gestures and the coach mark

Home-screen gesture ads live in one place, `launcher_config.gestures.{left_swipe, right_swipe,
drawer_open, app_launch, app_exit}` (`inter_enabled`, `inter_counter`, `url_enabled`, `url`), with
the matching `placements.{leftPanel, rightPanel, drawer, appExit}`. The coach mark is
`launcher_config.guide`. The old `app_click` / `swipe_right` / `swipe_left` / `home_hint` blocks
repeated those settings without being read, and have been removed.

### `right_panel.bottom_native` — the slot at the bottom of the swipe-in panel

| key | type | meaning |
|---|---|---|
| `enabled` | bool | off → frame stays gone, nothing preloaded. |
| `ad_type` | `native` \| `banner` \| `none` | which unit fills the frame. |
| `native_type` | `mid2` \| `mid` \| `big` \| `native_banner` | maps to `NativePromo.showMidNative2` / `showMidNative` / `showBigNative` / `NativePromoBanner.showNativeBannerNative`. Current behaviour = `mid2`. |
| `banner_type` | `adaptive` \| `inline` \| `normal` \| `collapsible` | used when `ad_type` is `banner`, same vocabulary as `ScreenAds.bannerType`. |
| `ad_unit_id` | string | **banner only.** The native renderers draw from one preloaded pool and take no per-call unit, so a native slot always uses the global `googleNative` (or its screen-wise id). Blank inherits the global `googleBanner`. |

### `default_home_screen` — the "Set as default launcher?" step

Where it sits in the flow is `onboarding.order`'s job; this block is only about its behaviour.

| key | type | meaning |
|---|---|---|
| `enabled` | bool | off → the step is skipped wherever `order` puts it. |
| `skip_if_default` | bool | already hold the HOME role → don't show the screen at all. |
| `skip_rest_on_grant` | bool | granting the role jumps straight to Home, skipping whatever `order` still had queued (today's behaviour). `false` → the remaining screens run anyway. |

### `onboarding.order` — the first-run sequence

```json
"order": ["welcome", "set_default", "intro", "language"]
```

The screens shown on first run, in this order. Names: `welcome`, `set_default`, `intro`,
`language`. The last one always leads to Home — the launcher home screen, or the caller-ID
app's own home when the audience block sets `"onboarding_home": "app"`. `set_default`,
`language` and `welcome` show a "Step n of m" header counted from this order.

- **Omitted = skipped.** `["welcome", "language"]` runs Welcome → Language → Home; the
  set-as-default ask and the intro carousel never appear.
- **Repeats allowed.** `["welcome", "set_default", "intro", "language", "set_default"]` asks
  for the default-home role early and, if the user skipped it and is still not default, once
  more at the end. A repeat that has nothing left to ask (already default, or `skip_if_default`)
  is dropped.
- **Unknown names are ignored**, so a typo costs one screen, not the flow.
- **Missing / empty `order`** falls back to `["set_default", "language", "welcome", "intro"]`.
- The per-screen gates still apply on top: a screen switched off by its own block
  (`default_home_screen.enabled: false`) or by `intro_display` cadence is skipped even when
  listed here.

### `right_panel.suggested_banner` — the slot under the panel's suggested apps

Same shape as `bottom_native`, **off unless the block is present**, and a `native_banner` by
default — it sits between two sections, so a tall renderer would push the recents and the
search results off the screen. It scrolls with the panel; `bottom_native` is the pinned one.

```json
"right_panel": {
  "bottom_native":     { "enabled": true, "ad_type": "native", "native_type": "mid2", "banner_type": "adaptive", "ad_unit_id": "" },
  "suggested_banner":  { "enabled": true, "ad_type": "native", "native_type": "native_banner", "banner_type": "adaptive", "ad_unit_id": "" }
}
```

### `app_drawer.bottom_native` — the slot at the bottom of the swipe-up drawer

Same shape as `right_panel.bottom_native`, but **off unless the block is present** — the drawer
shipped without an ad, so a config that says nothing leaves it that way. The frame rides the
app list as row 0, so it scrolls away with the apps rather than holding a strip of the drawer;
it spans the full grid width and the fast-scroller keeps working over it.

### `onboarding.<screen>` — per-screen ads on the first-run screens

Screens: `welcome`, `set_default`, `intro`, `language`.

| key | type | meaning |
|---|---|---|
| `inter_enabled` | bool | the ad on that screen's continue/skip action, per screen. |
| `ads_counter` | int | skip-then-show pacing for that screen's ad, own counter. |
| `ad_type` | `inter` \| `app_open` \| `reward` \| `link` \| `full_native` \| `custom` | which format that ad is. Blank / `inter` = the interstitial (or `placements.onboarding_<screen>.ad_flow` when that is set, e.g. `"app_open,reward,inter"`). `link` opens `placements.onboarding_<screen>.DirectLink` (falls back to `placements.onboarding`, then the global `DirectLink`). A link-first chain (`DirectLink` + `link_first_then`) still wins over all of these. |
| `skip_enabled` | bool | show the Skip button. `false` makes the screen a required step — the CTA is the only way forward (Back still behaves as it did). Default `true`. |
| `back_action` | `next_page` \| `next_screen` | `intro` only. `next_page` (default) = Back walks forward through the carousel, so every page is seen. `next_screen` = any Back leaves for the next screen straight away. **Paid (`OnMaketing`) users only** — for organic users Back is always plain Back on every onboarding screen (previous slide, then leave the screen). |
| `auto_next` | bool | `set_default` only. Role granted → move on by itself (default `true`); `false` waits for the CTA. |
| `auto_next_delay_ms` | int | `set_default` only. Delay before `auto_next` moves on. Default `650`, clamped 0–5000. |
| `slot.enabled` | bool | the on-screen ad frame (the mid native above the CTA today). |
| `slot.ad_type` | `native` \| `banner` \| `none` | native or banner in that frame. |
| `slot.native_type` | `mid` \| `mid2` \| `big` \| `native_banner` | which native renderer. |
| `slot.banner_type` | `adaptive` \| `inline` \| `normal` \| `collapsible` | banner size / collapsible. |
| `slot.ad_unit_id` | string | banner only (see above); a native slot keeps the global id. |

**Granting the role from the Settings list.** Picking this app in the system "Default home
app" list makes Android start the launcher, and the `set_default` screen is never resumed to
run its "Next" ad. That ad is then shown, once, on whichever screen comes up instead — the
launcher home, or the next onboarding step (`LauncherFlow.registerGrantAd`). Same
`onboarding.set_default` settings either way.

### `system_buttons` — ads on the system Home and Back buttons

```json
"system_buttons": {
  "home":    { "enabled": false, "ad_type": "", "ads_counter": 0, "min_gap_sec": 30 },
  "back":    { "enabled": false, "ad_type": "", "ads_counter": 0, "min_gap_sec": 30 },
  "recents": { "enabled": false }
}
```

Each button is switched on its own; a block that is absent is off.

| key | type | meaning |
|---|---|---|
| `enabled` | bool | that button's ad at all. |
| `ad_type` | `inter` \| `app_open` \| `reward` \| `link` \| `full_native` \| `custom` | one format. Blank = the button's placement chain: `placements.home` / `placements.back` (`ad_flow`, `DirectLink` + `link_first_then`, `googleInter` + `inter_fallback`). |
| `ads_counter` | int | presses skipped before the ad shows (`0` = every press that passes the gap). |
| `min_gap_sec` | int | minimum time between two ads on that button. Default `30`. |

- **home** — a Home press while the user was already in the app: on the launcher home itself,
  or from one of the app's own screens. Coming home from *another* app is the app-exit moment
  (`launcher_config.gestures.app_exit`, `placements.appExit`), never this one.
- **back** — Back on the bare launcher workspace (nothing open to close). Back inside the app's
  own screens, including the caller-ID home (`ShellActivity`) on its way out to the launcher, is
  the app-wide back ad: `IsBack`, `InterBackCounter`, `placements.back`.
- **recents** — only `enabled`. When present it replaces `recent_ad.enabled` as the switch for
  the Recents page below.

`placements.home.ads_on: false` mutes the Home ad without touching this block.

### `recent_ad` — the page shown when the app is reopened from Recents

`{ "enabled": false, "native": "off", "close_ad": "none", "min_gap_sec": 0 }`. A Recents press
in one of the app's own screens arms it; reopening the app's card shows the page. `close_ad`:
`inter` (with `placements.recent.ad_flow` / `inter_fallback`), `full_native`, `custom`,
`directlink`, `none`. Never armed from the launcher home — the home is not a Recents card.

### Recents → Play Store (audience root, outside `launcher_ads`)

| key | type | meaning |
|---|---|---|
| `recent_playstore` | bool | the first Recents press made inside the app opens the Play Store home instead. Once per install. Takes priority over `recent_ad`. |
| `recent_playstore_window_sec` | int | only within this many seconds of install. **`0` = no window**: the first Recents press whenever it comes. Default `180`. |

A press made while another app is in front never fires it and never spends it.

### `launcher_config.unlock_ads` — the ad after unlock

`{ "enabled": false, "start_after_hours": 24, "gap_minutes": 30, "max_per_day": 5, "format": "inter", "countries": { "IN": { "enabled": false } } }`.
Shown only on the launcher home or the app home, after onboarding. `format`: `inter`
(`placements.unlock.ad_flow` / link-first / `inter_fallback`), `full_native`, or one of
`app_open` / `reward` / `link` / `custom`. The App Open ad stands down for that foreground, so
an unlock never shows two ads.

### Outside `launcher_ads` — related switches

| key | where | meaning |
|-----|-------|---------|
| `panels.apps` | `launcher_config` | the whole apps panel (swipe + tap). `false` = it never opens. Default `true`. |
| `panels.apps_list` | `launcher_config` | the app list inside that panel: its Suggested and Recent grids. `false` hides both; greeting, search and the panel's ads stay. Default `true`. |
| `panels.host` | `launcher_config` | the panel holding this app's own screens. Default `true`. |
| `gestures.app_launch.inter_enabled` / `inter_counter` | `launcher_config` | master on/off and "every Nth tap" for the ad on an app tap (drawer, home grid, apps panel). |
| `placements.drawer.ads_on` | audience root | mutes the app-tap ad without touching the gesture. |
| `placements.drawer.ad_flow` | audience root | the app-tap chain, in order, e.g. `"inter,app_open,reward,full_native,directlink"`. A format left out is off. Names: `inter`, `app_open`, `reward`, `full_native`, `directlink`, `custom`. |
| `placements.drawer.ad_flow_show_all` | audience root | `true` = show every ready format in the chain back to back; `false` = the first one that is ready (waterfall). |
| `placements.drawer.link_first_then` | audience root | direct links first, then these formats. **Takes over from `ad_flow` when set** (and `DirectLink` + `IsCustomADS` are on) — remove it to use `ad_flow`. |
| `inter_loader` | audience root | the full-screen "loading ad" spinner while an interstitial / full-screen ad loads on demand. Falls back to the old `isLoaderForFB` when absent. |

## Notes

- Key naming keeps the exact spellings already in use: `snake_case` for the blocks, and
  `defaultHome` / `notDefaultHome` as written in the console.
- A missing block resolves to everything-off for that surface, and a missing key falls back to
  the default in the tables above — so a partial JSON never crashes the launcher.
- In DEBUG every resolution is logged under `LauncherAdsConfig`, including which variant
  (`defaultHome` / `notDefaultHome`) was merged; the first-run routing logs under
  `LauncherFlow`.

## The two audience flows

`launcher_ads` sits inside the `marketing` / `organic` split of the getData response (see
`ADDashboardActivity.audienceRoot`), so the paid and organic funnels are just two copies of the
block. Paste each into its own audience.

### Organic

Welcome → set as default → Home. No language picker, no intro carousel.

```json
"launcher_ads": {
  "right_panel": { "bottom_native": { "enabled": true,  "ad_type": "native", "native_type": "mid2", "banner_type": "adaptive", "ad_unit_id": "" } },
  "app_drawer":  { "bottom_native": { "enabled": false, "ad_type": "none",   "native_type": "mid2", "banner_type": "adaptive", "ad_unit_id": "" } },
  "default_home_screen": { "enabled": true, "skip_if_default": true, "skip_rest_on_grant": true },
  "onboarding": {
    "order": ["welcome", "set_default"],
    "welcome":     { "inter_enabled": false, "ads_counter": 0, "skip_enabled": true,
                     "slot": { "enabled": true,  "ad_type": "banner", "native_type": "mid", "banner_type": "adaptive", "ad_unit_id": "" } },
    "set_default": { "inter_enabled": false, "ads_counter": 0, "skip_enabled": true,
                     "slot": { "enabled": false, "ad_type": "none",  "native_type": "mid", "banner_type": "adaptive", "ad_unit_id": "" } }
  }
}
```

### Paid

Set as default → language → welcome → intro → Home, and the flow continues whether or not the
role is granted (`skip_rest_on_grant: false`).

```json
"launcher_ads": {
  "right_panel": { "bottom_native": { "enabled": true, "ad_type": "native", "native_type": "mid2", "banner_type": "adaptive", "ad_unit_id": "" } },
  "app_drawer":  { "bottom_native": { "enabled": true, "ad_type": "native", "native_type": "mid2", "banner_type": "adaptive", "ad_unit_id": "" } },
  "default_home_screen": { "enabled": true, "skip_if_default": true, "skip_rest_on_grant": false },
  "onboarding": {
    "order": ["set_default", "language", "welcome", "intro"],
    "set_default": { "inter_enabled": false, "ads_counter": 0, "skip_enabled": false,
                     "slot": { "enabled": true, "ad_type": "banner", "native_type": "mid", "banner_type": "adaptive", "ad_unit_id": "" } },
    "language":    { "inter_enabled": false, "ads_counter": 0,
                     "slot": { "enabled": true, "ad_type": "native", "native_type": "big", "banner_type": "adaptive", "ad_unit_id": "" } },
    "welcome":     { "inter_enabled": true,  "ads_counter": 0, "skip_enabled": true,
                     "slot": { "enabled": true, "ad_type": "banner", "native_type": "mid", "banner_type": "adaptive", "ad_unit_id": "" } },
    "intro":       { "inter_enabled": false, "ads_counter": 0, "skip_enabled": true, "back_action": "next_screen",
                     "slot": { "enabled": true, "ad_type": "native", "native_type": "big", "banner_type": "adaptive", "ad_unit_id": "" } }
  }
}
```

### The rest of each flow

Two things in these funnels live outside `launcher_ads`:

- **Splash ads** — `is_splash_ads` / `is_splash_inter_show`, both `false` for "Splash >> No Ads".
- **Permissions** — the `permission_engine` block, which is audience-split too. Each rule's
  `activities` list names the screens that ask for it:

  | flow | notification | phone state |
  |---|---|---|
  | organic | `["OnboardingWelcomeActivity"]` | `["OnboardingWelcomeActivity"]` |
  | paid | `["OnboardingWelcomeActivity"]` | `["OnboardingWelcomeActivity"]` |

  Dropping `LocaleActivity` from both lists is what makes the language picker
  permission-free; the intro carousel and the default-home screen never ask.

## Where it is implemented

| part | code |
|---|---|
| parsing, variant merge, pacing, slot rendering | `admesh/domain/LauncherAdsConfig.kt` |
| placement engine (`placements.*`, `ad_flow`, link-first) | `admesh/domain/LauncherPlacementAds.kt` → `admesh/presentation/DrawerAdRunner.kt` |
| gesture hooks | `launcher/…/activities/LauncherPanel.kt` (`onFlingRight` / `onFlingLeft`) → `launcher/…/promo/LauncherPromoController.kt` (`launcher_config.gestures`) |
| coach mark | `LauncherPanel.maybeShowGuide` (`launcher_config.guide`) |
| panel / drawer slots | `numberlookup/launcher/CallerLauncherAds.kt` |
| Home / Back buttons | `numberlookup/launcher/SystemButtonAds.kt`, via `LauncherBridge.onHomePressed` / `onWorkspaceBack` |
| Recents page, Recents → Play Store | `numberlookup/ui/recent/RecentAdWatcher.kt` |
| unlock | `numberlookup/launcher/UnlockAdWatcher.kt` |
| return from an app | `numberlookup/launcher/AppExitAd.kt`, from `LookupShellApp.handleAppForeground` |
| first-run order | `numberlookup/onboard/LauncherFlow.kt` (step index in fossify prefs `onboarding_step`) |
| onboarding screens | `OnboardingWelcomeActivity`, `OnboardingDefaultLauncherActivity`, `IntroActivity`, `LocaleActivity` |
