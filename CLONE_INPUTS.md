# Clone inputs — edit the "New value" column and tell me to apply

Source: `Old_Live_Apps/CallerID_Phone_Lookup` @ branch **v1.2** (versionCode 3)
Clone:  this folder, branch **v12**

Legend:
- **[SET]** — already applied. Change it only if you want something different.
- **[NEEDS YOU]** — a placeholder. The app builds, but this must be real before release.
- **[KEPT]** — deliberately unchanged. Reason given.

---

## A. Identity

| # | Item | Old (source) | Current value | New value |
|---|---|---|---|---|
| A1 | applicationId **[SET]** | `identifycaller.phonelookup.contacts.calllog` | `com.calleridapp.numberlookup` | |
| A2 | namespace **[SET]** | same as A1 | `com.calleridapp.numberlookup` | |
| A3 | Ad-module package **[SET]** | `...contacts.ap_ad_module` | `com.calleridapp.admesh` | |
| A4 | rootProject.name **[SET]** | `CallerID Phone Lookup` | `Caller ID Number Lookup Block` | |
| A5 | APK archive prefix **[SET]** | `CallerIdPhoneLookup` | `CallerIdNumberLookupBlock` | |
| A6 | Theme name **[SET]** | `Theme.CallerIDPhoneLookup` | `Theme.CallerLookupBlock` | |
| A7 | versionCode **[KEPT]** | 3 | 3 | |
| A8 | versionName **[KEPT]** | 1.2 | 1.2 | |
| A9 | minSdk / targetSdk **[KEPT]** | 24 / 36 | 24 / 36 | |

> A7/A8: kept so the clone matches the source feature set. For a **new Play listing** you almost certainly want versionCode 1 / versionName 1.0 — say the word.

## B. Names shown to users

| # | Item | Old | Current value | New value |
|---|---|---|---|---|
| B1 | `app_name` **[SET]** | `CallerID Phone Lookup` | `Caller ID: Number Lookup & Block` | |
| B2 | `app_label` (launcher) **[SET]** | `␣␣CallerID Phone Lookup` | `␣␣Caller ID: Number Lookup & Block` | |
| B3 | `app_name_overlay` **[SET]** | `CallerID Phone Lookup` | `Caller ID Lookup & Block` | |
| B4 | Localised `app_name` ×11 **[SET]** | localised old brand | localised new brand (ar es fr hi ja pt ru th tr vi zh) | |

> B2 keeps the source's two leading non-breaking spaces — they exist to sort the app to the top of system lists. Removing them changes that sort position.

## C. Brand colours

| # | Item | Old | Current value | New value |
|---|---|---|---|---|
| C1 | `primary` **[SET]** | `#2B5CE6` sapphire | `#00897B` teal | |
| C2 | `primary_dark` **[SET]** | `#1A46C4` | `#00695C` | |
| C3 | `primary_container` **[SET]** | `#E4EAFD` | `#D3EEEA` | |
| C4 | Splash gradient **[SET]** | `#2B5CE6 → #6C3DF4` | `#00897B → #0FBFA4` | |
| C5 | `teal` (tools accent) **[SET]** | `#0D9488` | `#5B54D6` indigo | |
| C6 | Verdict colours **[KEPT]** | green/red/amber | unchanged | |
| C7 | `accent_purple` **[KEPT]** | `#6C3DD9` | unchanged | |
| C8 | Launcher icon **[SET]** | blue handset + person bubble | new teal handset + block badge, 5 densities + adaptive | |

> C5: the source's `teal` would now clash with the teal primary, so the tools accent moved to indigo.
> C6: the design system pins these to meaning ("identified"/"spam"/"suspicious"), not to the brand — recolouring them would break that.

## D. Keys and endpoints

| # | Item | Where | Value | Status |
|---|---|---|---|---|
| D1 | Backend base URL | `services/RetrofitClient.kt` | `https://callerid.kpeworld.com/` | **[SET]** restored from source |
| D2 | API account id | `services/ServiceCredentials.kt` | `1433` | **[SET]** restored from source |
| D3 | API hash key | `services/ServiceCredentials.kt` | (28 chars) | **[SET]** restored from source |
| D4 | API bearer token | `services/ServiceCredentials.kt` | (132-char JWT) | **[SET]** restored from source |
| D5 | LightHouse API key | `local.properties` | `sk_wmylvq…` | **[SET]** new key you supplied |
| D6 | LightHouse base URL | `local.properties` | same endpoint as source | **[SET]** |
| D7 | `google-services.json` | `app/` | package_name rewritten; still project `callerid-phone-lookup` (`621456667679`) | **[NEEDS YOU]** |

> D1–D4 are your own backend (`kpeworld.com`), so the clone keeps using it. The
> `ServiceCredentials.isConfigured` guard stays in place and now returns true;
> it only exists so a build with placeholders can never fire those calls —
> notably `PersonUploader`, which uploads the user's contacts.
>
> D5: the source uses a different key (`sk_d4j…`), so the two apps report separately. Correct for a distinct app.
>
> D7 is the one thing still outstanding: register `com.calleridapp.numberlookup`
> in a Firebase project and drop in the real file, or Analytics, Crashlytics and
> Remote Config will not report.

## E. Deliberately left alone — **[KEPT]**

| # | Item | Where | Why |
|---|---|---|---|
| E1 | AdMob `APPLICATION_ID` | `AndroidManifest.xml` | Already Google's official **test** id in the source. Swap for your own before release. |
| E2 | `conduit.user` / `conduit.password` | `gradle.properties` | Private-maven read credentials — the build cannot resolve the LightHouse SDK without them. |
| E3 | Signing keystore | `certificate/calleridphonelookup` | The source app's key. Use your own for a separate Play listing. |
| E4 | Ad-SDK native layouts (9) | `res/layout/google*native*.xml`, `fb*native*.xml` | AdMob/FAN bind these views by reference; renaming their ids breaks ad rendering. |

---

## Two decisions worth making now

1. **versionCode / versionName** (A7/A8) — keep 3 / 1.2, or reset to 1 / 1.0 for a fresh listing?
2. **Remote Config activity names** — the permission engine matches Activity *simple names* sent from Firebase. I renamed the activities and added a 28-entry legacy alias table so your existing Remote Config keeps working. Alternative: skip the aliases and update Remote Config server-side instead.
