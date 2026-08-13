# `launcher_ads` — Remote Config schema (v2)

One Remote Config string parameter, `launcher_ads`, owning everything the launcher does
around ads, hints and the first-run route. Stored as JSON text in AdsVault by
`ADDashboardActivity.ingestConfig` and read back with `JSONObject`, same as `intro_display`
and `ScreenAds`.

## Paste-ready value

```json
{
  "app_click": {
    "enabled": true,
    "ad_type": "inter",
    "inter_enabled": true,
    "ads_counter": 3,
    "fallback_link_enabled": true,
    "fallback_link": "https://980.mark.qureka.com/intro"
  },

  "swipe_right": {
    "enabled": true,
    "ad_type": "inter",
    "inter_enabled": true,
    "ads_counter": 3,
    "fallback_link_enabled": false,
    "fallback_link": ""
  },

  "swipe_left": {
    "enabled": true,
    "ad_type": "inter",
    "inter_enabled": true,
    "ads_counter": 3,
    "fallback_link_enabled": false,
    "fallback_link": ""
  },

  "home_hint": {
    "enabled": true,
    "swipeHints": ["right", "left", "up"],
    "show_mode": "once",
    "interval": 0,
    "auto_hide_sec": 0
  },

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
    "swipe_right": { "ads_counter": 1 },
    "home_hint": { "swipeHints": ["right", "left", "up"] }
  },

  "notDefaultHome": {
    "home_hint": { "enabled": false },
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
inherits from the base — at every level, so `{"swipe_right": {"ads_counter": 1}}` changes only
the counter and leaves `enabled`, `ad_type`, `inter_enabled` and the fallback link alone.
`{}` (or a missing variant) means "use the base unchanged". Arrays are replaced whole, not
merged — `swipeHints` in a variant fully replaces the base list.

So: change one field inside either block and only that field flips. That is the
"any one change inside automatically override" behaviour.

## Key reference

### Gesture surfaces — `app_click`, `swipe_right`, `swipe_left`

| key | type | meaning |
|---|---|---|
| `enabled` | bool | master kill-switch for the gesture. Off → gesture does its normal thing, nothing ad-related attempted. |
| `ad_type` | `inter` \| `link` \| `none` | what fills the ad moment. `inter` = interstitial (falls back to the link only if the interstitial is switched off), `link` = open `fallback_link` directly, `none` = nothing. Absent → derived from `inter_enabled` (back-compat). |
| `inter_enabled` | bool | legacy switch, still honoured. `ad_type` wins when both are present. |
| `ads_counter` | int | events SKIPPED before the ad moment lands. `3` → fires on the 4th, `0` → every time. Counted in prefs, so it survives the launcher process being killed. Never advances when nothing is showable. |
| `fallback_link_enabled` | bool | allow the link to stand in. |
| `fallback_link` | string | URL opened when the link is the chosen filler. |

`swipe_left` is the new one: the left fling that slides the app-search panel in from the
right. Same shape, its own independent counter.

Gate order is unchanged — these sit on top of `InterstitialNormal.showInterAds`, which still
applies the network check, `IsAdsON`, `InterAds` and the global `InterCounter`. The gesture
always completes: the ad callback fires on every path (shown, skipped, no fill, no network).

### `home_hint` — the home-screen coach mark

| key | type | meaning |
|---|---|---|
| `enabled` | bool | show the hint overlay at all. |
| `swipeHints` | array | which gestures to teach, **one at a time, in this order**: `right` (opens the caller-ID app), `left` (app-search panel), `up` (app drawer), `down` (notification shade). An empty array behaves like `enabled: false`. |
| `show_mode` | `once` \| `always` \| `app_launches` | `once` = one pass through the list, ever; `always` = a fresh pass each launch; `app_launches` = skip-then-show pacing on `interval`. |
| `interval` | int | the X for `app_launches`: launches SKIPPED before a new pass starts (`3` → the 4th). `0` = every launch. Ignored for the other modes. |
| `auto_hide_sec` | int | take the current hint off the screen after N seconds. The run does not advance — the same gesture is offered again next time the home screen comes back. `0` = stays until the user swipes. |

**One gesture at a time.** The first entry is shown alone and stays until the user actually
makes that swipe; the next entry appears once they are back on the bare home screen (returning
from the caller-ID app, closing the panel or the drawer), and so on until the list is done.
Any other gesture leaves the hint where it is. How far the run has got lives in
`Config.swipeHintIndex`, so an interrupted `once` run resumes rather than starting over, and
`Config.wasSwipeHintShown` latches only when the whole list has been taught.

A chevron trio plus caption, drifting the way it is teaching. The captions are
`swipe_right_hint` / `swipe_left_hint` / `swipe_up_hint` / `swipe_down_hint`.

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
`language`. The last one always leads to the launcher home screen.

- **Omitted = skipped.** `["welcome", "language"]` runs Welcome → Language → Home; the
  set-as-default ask and the intro carousel never appear.
- **Repeats allowed.** `["welcome", "set_default", "intro", "language", "set_default"]` asks
  for the default-home role early and, if the user skipped it and is still not default, once
  more at the end. A repeat that has nothing left to ask (already default, or `skip_if_default`)
  is dropped.
- **Unknown names are ignored**, so a typo costs one screen, not the flow.
- **Missing / empty `order`** falls back to `["welcome", "set_default", "intro", "language"]`.
- The per-screen gates still apply on top: a screen switched off by its own block
  (`default_home_screen.enabled: false`) or by `intro_display` cadence is skipped even when
  listed here.

### `onboarding.<screen>` — per-screen ads on the first-run screens

Screens: `welcome`, `set_default`, `intro`, `language`.

| key | type | meaning |
|---|---|---|
| `inter_enabled` | bool | interstitial on that screen's continue/skip action, per screen. |
| `ads_counter` | int | skip-then-show pacing for that screen's interstitial, own counter. |
| `slot.enabled` | bool | the on-screen ad frame (the mid native above the CTA today). |
| `slot.ad_type` | `native` \| `banner` \| `none` | native or banner in that frame. |
| `slot.native_type` | `mid` \| `mid2` \| `big` \| `native_banner` | which native renderer. |
| `slot.banner_type` | `adaptive` \| `inline` \| `normal` \| `collapsible` | banner size / collapsible. |
| `slot.ad_unit_id` | string | banner only (see above); a native slot keeps the global id. |

## Notes

- Key naming keeps the exact spellings already in use: `snake_case` for the gesture blocks
  that shipped in v1, and `swipeHints` / `defaultHome` / `notDefaultHome` as written in the
  console.
- A missing block resolves to everything-off for that surface, and a missing key falls back to
  the default in the tables above — so a partial JSON never crashes the launcher.
- In DEBUG every resolution is logged under `LauncherAdsConfig`, including which variant
  (`defaultHome` / `notDefaultHome`) was merged; the first-run routing logs under
  `LauncherFlow`.

## Where it is implemented

| part | code |
|---|---|
| parsing, variant merge, pacing, slot rendering | `admesh/domain/LauncherAdsConfig.kt` |
| gesture hooks | `MainActivity.onFlingRight` / `onFlingLeft`, `AllAppsFragment`, `LeftPanelFragment` |
| coach mark | `MainActivity.maybeShowSwipeHint` + `res/layout/item_swipe_hint.xml` |
| panel slot | `LeftPanelFragment.onPanelShown` |
| first-run order | `launcher/helpers/LauncherFlow.kt` (step index in `Config.onboardingStep`) |
| onboarding screens | `OnboardingWelcomeActivity`, `OnboardingDefaultLauncherActivity`, `IntroActivity`, `LocaleActivity` |
