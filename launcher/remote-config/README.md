# Remote Config for the launcher module

Two files, and only one of them is a parameter of its own.

| File | Goes where | Required? |
|---|---|---|
| `launcher_config.json` | a Remote Config parameter named `launcher_config` | **Yes**, if you want to control the launcher from the console |
| `ads_config_launcher_entries.json` | merged **into** the host's existing `ads_config` | Only if the launcher shows ads |

Nothing else needs a parameter. The OEM home-layout match (`home_profile_rules`,
`home_profile_probes`) ships its full table **inside the module** and works with no console setup at
all; those two keys exist only as optional overrides for when a new device needs a rule before the
next release, and a host that never creates them is fully supported.

## Setting it up

1. Firebase console → Remote Config → **Add parameter**
2. Name it `launcher_config`, type **JSON**, paste `launcher_config.json`
3. Publish
4. Wire the host's reader:

```kotlin
override fun configString(key: String, fallback: String): String =
    FirebaseRemoteConfig.getInstance().getString(key).ifBlank { fallback }
```

If you keep a debug twin (`debug_launcher_config`), return that one in debug builds — the module
asks for a key by name and does not care where the string came from.

## Everything is off by default

Every ad field defaults to **off** and every prompt defaults to **on**. That is deliberate: a
parameter that has not been fetched yet, or one that arrives malformed, must never be able to start
showing ads on its own, and must never silently disable a prompt the launcher needs.

This matters more than it sounds, because **nothing is readable at the top of a cold start**. The
first launch of a fresh install reads code defaults while a dump seconds later prints the real
values. Test config-driven behaviour on the *second* launch.

## The audience split

Each blob carries an `organic` and a `marketing` branch, picked by
`LauncherBridge.isOrganicAudience()`. A blob with no branches at all is read flat and applies to
both, so an older template keeps working after a key gains its split.

## `launcher_config` fields

```
os_style                  bool   true lays the home out like the launcher being replaced (grid,
                                 drawer, icon sizes read per OS, brand and model); false, and
                                 unset, is the module's own 4x6 setup with every app on the pages.
                                 A change is re-applied to homes already built.
search_widget             str    the first page's search bar: "google" (and unset, or anything
                                 unrecognised) or "chrome". A change swaps the bar on homes
                                 already built.
default_launcher_prompt   bool   the "set as default home" card on the workspace
panels.apps               bool   the app-search panel (fling left)
panels.host               bool   the host app's panel (fling right); off = the gesture does nothing
guide.enabled             bool   master switch for the coach marks
guide.<step>              bool   one per step: swipe_right_contacts, swipe_left_apps, swipe_up_drawer
gestures.<name>           obj    left_swipe | right_swipe | drawer_open
  .inter_enabled          bool   ask the host for an interstitial on this gesture
  .inter_counter          int    how many of THIS gesture between ads; 0 = every time
  .url_enabled            bool   open a promo link instead of an ad
  .url                    str    the link
unlock_ads                obj    optional; passed through to the host untouched as raw JSON
```

`panels.host` was spelled `messages` while the launcher lived in a messaging app. Both spellings
are read; `host` wins when a blob carries both.

## The optional override keys

Create these only when you need to change device matching without shipping a build. Same reader,
same bridge — the module falls back to its own built-in table when they are absent or malformed.

| Key | Overrides |
|---|---|
| `home_profile_rules` | which OEM profile applies to which device |
| `home_profile_probes` | where to look inside an OEM launcher's APK for its grid |

To create one, dump the built-in table as your starting point: `HomeProfileRules.DEFAULT_RULES_JSON`
and `ApkProfileReader.DEFAULT_PROBES_JSON` in the module source.

## Gotchas worth knowing before debugging a gate

- **Read whichever store the host actually fills.** A parameter can sit in `FirebaseRemoteConfig`
  and still be absent from an ads SDK's ingested preferences, which only carry an explicit key list.
  Whatever `configString` returns is the only truth the launcher has.
- **A host's own master ad switch outranks all of this.** If the host's SDK is off, every
  interstitial is silenced no matter how the gestures are configured. It is the most common cause of
  a config that looks right and does nothing.
- **No more than one native format per screen.** Two native slots enabled on one surface is the
  usual reason a slot logs a request and never fills.
