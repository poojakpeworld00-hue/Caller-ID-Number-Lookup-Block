package io.launcher.home.profile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeProfileRulesTest {

    private fun fp(
        homePackage: String = "",
        versionCode: Long = 0,
        manufacturer: String = "",
        brand: String = manufacturer,
        model: String = "",
        sdk: Int = 34,
        swDp: Int = 411,
        osVersion: String = "",
        device: String = "",
        widthDp: Int = 411,
        densityDpi: Int = 420,
    ) = LauncherFingerprint(
        homePackage = homePackage,
        homeVersionCode = versionCode,
        homeVersionName = "",
        manufacturer = manufacturer,
        brand = brand,
        model = model,
        device = device,
        sdk = sdk,
        swDp = swDp,
        navMode = 2,
        widthDp = widthDp,
        heightDp = 900,
        densityDpi = densityDpi,
        osVersion = osVersion,
    )

    private val builtIn get() = HomeProfileRules.builtIn

    @Test
    fun builtInTableParses() {
        assertFalse(builtIn.disabled)
        assertTrue(builtIn.rules.size > 10)
        assertTrue(HomeProfileRules.isKnownLauncher("com.sec.android.app.launcher"))
        assertTrue(HomeProfileRules.isKnownLauncher("com.miui.home"))
        assertFalse(HomeProfileRules.isKnownLauncher("com.example.nothing"))
    }

    @Test
    fun samsungPhoneByPackage() {
        val p = builtIn.select(fp(homePackage = "com.sec.android.app.launcher", manufacturer = "samsung"))!!
        assertEquals("samsung_phone", p.id)
        assertEquals(4, p.homeCols)
        assertEquals(5, p.homeRows)
        assertEquals(DrawerMode.PAGED, p.drawerMode)
        assertEquals(ProfileSource.BUILT_IN, p.source)
    }

    @Test
    fun samsungTabletBeatsGenericTabletAndPhoneRows() {
        val p = builtIn.select(fp(homePackage = "com.sec.android.app.launcher", manufacturer = "samsung", swDp = 800))!!
        assertEquals("samsung_tablet", p.id)
        assertEquals(6, p.homeCols)
    }

    @Test
    fun unknownTabletGetsGenericTabletRowOverBrandRow() {
        // Xiaomi tablet: the generic tablet row and the miui package row both state one key; the
        // generic one is first in the table and so wins the tie.
        val p = builtIn.select(fp(homePackage = "com.miui.home", manufacturer = "xiaomi", swDp = 800))!!
        assertEquals("aosp_tablet", p.id)
    }

    @Test
    fun miuiPhoneHasNoDrawer() {
        val p = builtIn.select(fp(homePackage = "com.miui.home", manufacturer = "xiaomi"))!!
        assertEquals("miui_classic", p.id)
        assertEquals(DrawerMode.NONE, p.drawerMode)
        assertEquals(4, p.homeCols)
        assertEquals(6, p.homeRows)
    }

    @Test
    fun pixelRowHasFiveDockSlots() {
        val p = builtIn.select(fp(homePackage = "com.google.android.apps.nexuslauncher", manufacturer = "google"))!!
        assertEquals("pixel_phone", p.id)
        assertEquals(5, p.dockSize)
    }

    @Test
    fun everyFieldNamesItsTier() {
        val standard = HomeProfile.AOSP
        assertEquals(ProfileSource.STANDARD, standard.sourceOf(HomeProfile.KEY_HOME_COLS))
        val documented = builtIn.select(fp(homePackage = "com.miui.home"))!!
        assertEquals(ProfileSource.BUILT_IN, documented.sourceOf(HomeProfile.KEY_DRAWER_MODE))
        val read = documented.overlay(HomeProfile.Numbers(homeCols = 4, homeRows = 6))
        assertEquals(ProfileSource.APK, read.sourceOf(HomeProfile.KEY_HOME_COLS))
        assertEquals(ProfileSource.APK, read.sourceOf(HomeProfile.KEY_HOME_ROWS))
        assertEquals(ProfileSource.BUILT_IN, read.sourceOf(HomeProfile.KEY_DRAWER_MODE))
        assertEquals(ProfileSource.BUILT_IN, read.sourceOf(HomeProfile.KEY_DOCK_SIZE))
        assertTrue(read.describeSources().contains("home_cols=apk"))
        assertTrue(read.describeSources().contains("drawer_mode=built_in"))
    }

    @Test
    fun samsungOneUi7MovesTheDrawerSearchToTheBottom() {
        val old = builtIn.select(fp(homePackage = "com.sec.android.app.launcher", osVersion = "60101"))!!
        assertEquals("samsung_phone", old.id)
        assertEquals(DrawerSearchPosition.TOP, old.drawerSearchPosition)
        val new = builtIn.select(fp(homePackage = "com.sec.android.app.launcher", osVersion = "70000"))!!
        assertEquals("samsung_phone_oneui7", new.id)
        assertEquals(DrawerSearchPosition.BOTTOM, new.drawerSearchPosition)
        // No skin version readable: the safer, older row.
        assertEquals("samsung_phone", builtIn.select(fp(homePackage = "com.sec.android.app.launcher"))!!.id)
    }

    @Test
    fun samsungOneUi7DrawsA4x6HomeWith11spLabels() {
        // One UI Home 17.5.05: GridList.phoneGridList = [4x6, 5x6]; PhoneItemStyleFactory 4x6 label = 11 dp.
        val new = builtIn.select(fp(homePackage = "com.sec.android.app.launcher", osVersion = "80500"))!!
        assertEquals("samsung_phone_oneui7", new.id)
        assertEquals(4, new.homeCols)
        assertEquals(6, new.homeRows)
        assertEquals(11f, new.labelSp, 0.001f)
        assertEquals(ProfileSource.BUILT_IN, new.sourceOf(HomeProfile.KEY_LABEL_SP))
        // Older One UI: nothing known about the label, so ours.
        val old = builtIn.select(fp(homePackage = "com.sec.android.app.launcher", osVersion = "60101"))!!
        assertEquals(0f, old.labelSp, 0.001f)
        assertEquals(5, old.homeRows)
    }

    @Test
    fun labelSizeFromTheLauncherApkBeatsTheRow() {
        val row = builtIn.select(fp(homePackage = "com.sec.android.app.launcher", osVersion = "80500"))!!
        val over = row.overlay(HomeProfile.Numbers(labelSp = 12.5f))
        assertEquals(12.5f, over.labelSp, 0.001f)
        assertEquals(ProfileSource.APK, over.sourceOf(HomeProfile.KEY_LABEL_SP))
        // Out of range from the APK: ignored, the row's value stands.
        assertEquals(11f, row.overlay(HomeProfile.Numbers(labelSp = 40f)).labelSp, 0.001f)
        assertEquals(ProfileSource.BUILT_IN, row.overlay(HomeProfile.Numbers(labelSp = 40f)).sourceOf(HomeProfile.KEY_LABEL_SP))
        assertTrue(!HomeProfile.Numbers(labelSp = 11f).isEmpty)
    }

    @Test
    fun iconDpFromTheLauncherApkIsCarriedAsIs() {
        val row = builtIn.select(fp(homePackage = "com.sec.android.app.launcher", osVersion = "80500"))!!
        assertEquals(0, row.iconDp)
        val over = row.overlay(HomeProfile.Numbers(iconSizeDp = 63), ownIconDp = { 72f })
        assertEquals(63, over.iconDp)
        assertEquals(ProfileSource.APK, over.sourceOf(HomeProfile.KEY_ICON_DP))
        assertEquals(63, HomeProfile.fromJson(JSONObject(over.toJson().toString()), source = ProfileSource.BUILT_IN)!!.iconDp)
        assertNull(HomeProfile.fromJson(JSONObject("""{"id":"x","icon_dp":200}"""), source = ProfileSource.RC_RULE))
    }

    @Test
    fun oneUiIconsAreAFractionOfTheWidthResolvedPerDevice() {
        val row = builtIn.select(fp(homePackage = "com.sec.android.app.launcher", osVersion = "80500"))!!
        assertEquals(0.164f, row.iconWidthFraction, 0.0001f)
        assertEquals(0.95f, row.iconInset, 0.0001f)
        assertEquals(0.161f, row.iconFractionFor(420), 0.0001f)
        assertEquals(0.168f, row.iconFractionFor(480), 0.0001f)
        assertEquals(0.164f, row.iconFractionFor(0), 0.0001f)
        assertEquals(0, row.dockIconDp); assertEquals(0, row.drawerIconDp)
        // Galaxy A35 at 420 dpi, 411 dp wide: item 0.161 x 411 = 66 dp, drawn at 0.95 = 63 dp = 165 px
        // (stock art measured 161 px; ours renders ~2% inside the size it is given).
        val resolved = row.resolveFractions(411, 420)
        assertEquals(66, resolved.iconDp)
        assertEquals(63, resolved.homeArtDp); assertEquals(63, resolved.dockArtDp); assertEquals(63, resolved.drawerArtDp)
        // The fraction wins over the APK's 63 dp item (that dimen is the default-zoom value only).
        val withApk = row.overlay(HomeProfile.Numbers(iconSizeDp = 63)).resolveFractions(411, 420)
        assertEquals(66, withApk.iconDp)
        assertEquals(63, withApk.homeArtDp); assertEquals(63, withApk.dockArtDp); assertEquals(63, withApk.drawerArtDp)
        // 480 dpi (360 dp wide): 0.168 x 360 = 60 dp item, 57 dp art.
        assertEquals(60, row.resolveFractions(360, 480).iconDp)
        assertEquals(57, row.resolveFractions(360, 480).drawerArtDp)
        // No density known: the flat 0.164.
        assertEquals(67, row.resolveFractions(411).iconDp)
        // The per-density table survives the saved-profile round trip.
        assertEquals(row.iconWidthFractionByDpi, HomeProfile.fromJson(JSONObject(row.toJson().toString()), source = ProfileSource.BUILT_IN)!!.iconWidthFractionByDpi)
        assertNull(HomeProfile.fromJson(JSONObject("""{"id":"x","icon_width_fraction_by_dpi":{"420":0.9}}"""), source = ProfileSource.RC_RULE))
        assertNull(HomeProfile.fromJson(JSONObject("""{"id":"x","icon_inset":0.3}"""), source = ProfileSource.RC_RULE))
        assertEquals(1f * 63, HomeProfile.AOSP.copy(iconDp = 63).homeArtDp.toFloat(), 0.001f)
        assertEquals(withApk.sourceOf(HomeProfile.KEY_ICON_WIDTH_FRACTION), withApk.sourceOf(HomeProfile.KEY_ICON_DP))
        assertEquals(withApk, withApk.resolveFractions(411, 420))
        // A launcher that states a dock fraction of its own resolves it apart.
        val table = HomeProfileRules.parse("""[{"match":{},"profile":{"id":"d","dock_icon_width_fraction":0.173}}]""", ProfileSource.RC_RULE)!!
        val dock = table.select(fp())!!.resolveFractions(411)
        assertEquals(71, dock.dockIconDp); assertEquals(71, dock.dockArtDp); assertEquals(0, dock.homeArtDp)
        // Legacy-icon tray: One UI rows white, everything else the art's colour; a typo drops the row.
        assertEquals(HomeProfile.LEGACY_TRAY_WHITE, row.legacyIconTray)
        assertEquals(HomeProfile.LEGACY_TRAY_DOMINANT, HomeProfile.AOSP.legacyIconTray)
        assertEquals(HomeProfile.LEGACY_TRAY_WHITE, builtIn.select(fp(homePackage = "com.sec.android.app.launcher"))!!.legacyIconTray)
        assertEquals(HomeProfile.LEGACY_TRAY_DOMINANT, builtIn.select(fp(homePackage = "com.google.android.apps.nexuslauncher"))!!.legacyIconTray)
        assertNull(HomeProfile.fromJson(JSONObject("""{"id":"x","legacy_icon_tray":"blue"}"""), source = ProfileSource.RC_RULE))
        assertEquals("white", HomeProfile.fromJson(JSONObject(builtIn.select(fp(homePackage = "com.sec.android.app.launcher"))!!.toJson().toString()), source = ProfileSource.BUILT_IN)!!.legacyIconTray)
    }

    @Test
    fun launcher3StatesDrawerAndDockIconsApart() {
        val over = HomeProfile.AOSP.overlay(HomeProfile.Numbers(iconSizeDp = 60, drawerIconDp = 56, dockIconDp = 62))
        assertEquals(60, over.iconDp); assertEquals(56, over.drawerIconDp); assertEquals(62, over.dockIconDp)
        assertEquals(ProfileSource.APK, over.sourceOf(HomeProfile.KEY_DRAWER_ICON_DP))
        val back = HomeProfile.fromJson(JSONObject(over.toJson().toString()), source = ProfileSource.BUILT_IN)!!
        assertEquals(56, back.drawerIconDp); assertEquals(62, back.dockIconDp)
        assertNull(HomeProfile.fromJson(JSONObject("""{"id":"x","dock_icon_width_fraction":0.9}"""), source = ProfileSource.RC_RULE))
    }

    @Test
    fun labelSizeInARowMustBeReadable() {
        assertNull(HomeProfile.fromJson(JSONObject("""{"id":"x","label_sp":30}"""), source = ProfileSource.RC_RULE))
        assertNull(HomeProfile.fromJson(JSONObject("""{"id":"x","label_sp":5}"""), source = ProfileSource.RC_RULE))
        assertEquals(9.5f, HomeProfile.fromJson(JSONObject("""{"id":"x","label_sp":9.5}"""), source = ProfileSource.RC_RULE)!!.labelSp, 0.001f)
        assertEquals(0f, HomeProfile.fromJson(JSONObject("""{"id":"x"}"""), source = ProfileSource.RC_RULE)!!.labelSp, 0.001f)
    }

    @Test
    fun perDeviceKeysDeviceModelOsDensityWidth() {
        val table = HomeProfileRules.parse(
            """
            [
              { "match": { "manufacturer": "samsung" }, "profile": { "id": "brand" } },
              { "match": { "manufacturer": "samsung", "device": ["dm3q", "dm2q"] }, "profile": { "id": "s23" } },
              { "match": { "manufacturer": "samsung", "model": "SM-A546E" }, "profile": { "id": "a54" } },
              { "match": { "manufacturer": "samsung", "os_version_prefix": "7" }, "profile": { "id": "oneui7" } },
              { "match": { "manufacturer": "samsung", "density_min": 480, "width_dp_max": 380 }, "profile": { "id": "dense_small" } },
              { "match": { "manufacturer": "samsung", "nav_mode": 2, "device": "dm3q" }, "profile": { "id": "s23_gesture" } }
            ]
            """.trimIndent(), ProfileSource.RC_RULE
        )!!
        assertEquals("brand", table.select(fp(manufacturer = "samsung"))!!.id)
        assertEquals("s23", table.select(fp(manufacturer = "samsung", device = "DM2Q"))!!.id)
        assertEquals("a54", table.select(fp(manufacturer = "samsung", model = "sm-a546e"))!!.id)
        assertEquals("oneui7", table.select(fp(manufacturer = "samsung", osVersion = "70000"))!!.id)
        assertEquals("dense_small", table.select(fp(manufacturer = "samsung", densityDpi = 560, widthDp = 360))!!.id)
        assertEquals("brand", table.select(fp(manufacturer = "samsung", densityDpi = 560, widthDp = 411))!!.id)
        // three keys beat two: the gesture-nav S23 row wins over the plain S23 row
        assertEquals("s23_gesture", table.select(fp(manufacturer = "samsung", device = "dm3q"))!!.id)
    }

    @Test
    fun osVersionNumbers() {
        assertEquals(60101L, OemOsVersion.toNumber("60101"))
        assertEquals(140000L, OemOsVersion.toNumber("V14.0.0"))
        assertEquals(140002L, OemOsVersion.toNumber("V14.0.2"))
        assertEquals(20000L, OemOsVersion.toNumber("OS2.0"))
        assertEquals(130000L, OemOsVersion.toNumber("EmotionUI_13.0.0"))
        assertEquals(0L, OemOsVersion.toNumber(""))
        assertEquals(0L, OemOsVersion.toNumber("beta"))
    }

    @Test
    fun iconScaleComesFromTheLauncherIconSize() {
        val base = HomeProfile.AOSP
        // 5 columns on a 411 dp display: our icon is 411/5 - 2*(10*5/5) = 62.2 dp; a 56 dp Launcher3 icon scales to 0.9.
        val over = base.overlay(HomeProfile.Numbers(iconSizeDp = 56), ownIconDp = { cols -> 411f / cols - 2 * (10f * 5 / cols) })
        assertEquals(0.90f, over.iconScale, 0.01f)
        assertEquals(ProfileSource.APK, over.source)
        // Clamped, never absurd.
        assertEquals(HomeProfile.MAX_ICON_SCALE, base.overlay(HomeProfile.Numbers(iconSizeDp = 120), ownIconDp = { 40f }).iconScale, 0.001f)
        // No size known: scale untouched.
        assertEquals(1f, base.overlay(HomeProfile.Numbers(homeCols = 4)).iconScale, 0.001f)
    }

    @Test
    fun brandFallbackWhenLauncherPackageUnknown() {
        val p = builtIn.select(fp(homePackage = "", manufacturer = "realme"))!!
        assertEquals("coloros", p.id)
        val q = builtIn.select(fp(homePackage = "com.some.oem.home", manufacturer = "infinix"))!!
        assertEquals("transsion", q.id)
    }

    @Test
    fun brandMatchesAgainstBrandField() {
        val p = builtIn.select(fp(manufacturer = "xiaomi", brand = "poco"))!!
        assertEquals("miui_classic", p.id)
    }

    @Test
    fun nothingMatchesGivesNull() {
        assertNull(builtIn.select(fp(homePackage = "com.unknown", manufacturer = "acme")))
    }

    @Test
    fun thirdPartyLauncherFallsToAospNumbers() {
        val p = builtIn.select(fp(homePackage = "com.teslacoilsw.launcher", manufacturer = "samsung"))!!
        assertEquals("third_party", p.id)
        assertEquals(HomeProfile.AOSP.homeCols, p.homeCols)
        assertEquals(HomeProfile.AOSP.drawerMode, p.drawerMode)
    }

    @Test
    fun versionAndSdkRangesAndSpecificity() {
        val table = HomeProfileRules.parse(
            """
            [
              { "match": { "package": "x" }, "profile": { "id": "any", "home_cols": 4 } },
              { "match": { "package": "x", "version_code_min": 100, "version_code_max": 199 }, "profile": { "id": "v1", "home_cols": 5 } },
              { "match": { "package": "x", "sdk_min": 35, "model_prefix": "SM-F" }, "profile": { "id": "fold", "home_cols": 6 } }
            ]
            """.trimIndent(), ProfileSource.RC_RULE
        )!!
        assertEquals("any", table.select(fp(homePackage = "x", versionCode = 50))!!.id)
        assertEquals("v1", table.select(fp(homePackage = "x", versionCode = 150))!!.id)
        assertEquals("any", table.select(fp(homePackage = "x", versionCode = 250))!!.id)
        assertEquals("fold", table.select(fp(homePackage = "x", versionCode = 150, sdk = 35, model = "sm-f946b"))!!.id)
        assertEquals("v1", table.select(fp(homePackage = "x", versionCode = 150, sdk = 34, model = "SM-F946B"))!!.id)
        assertEquals(ProfileSource.RC_RULE, table.select(fp(homePackage = "x"))!!.source)
    }

    @Test
    fun objectFormWithDisabledFlag() {
        val table = HomeProfileRules.parse("""{"disabled": true, "rules": []}""", ProfileSource.RC_RULE)!!
        assertTrue(table.disabled)
        assertTrue(table.rules.isEmpty())
    }

    @Test
    fun garbageAndBlankAreNull() {
        assertNull(HomeProfileRules.parse("", ProfileSource.RC_RULE))
        assertNull(HomeProfileRules.parse("   ", ProfileSource.RC_RULE))
        assertNull(HomeProfileRules.parse("not json", ProfileSource.RC_RULE))
    }

    @Test
    fun insaneProfileRowIsDropped() {
        val table = HomeProfileRules.parse(
            """[
              { "match": {}, "profile": { "id": "zero", "home_cols": 0 } },
              { "match": {}, "profile": { "home_cols": 4 } },
              { "match": {}, "profile": { "id": "ok", "home_cols": 4 } }
            ]""", ProfileSource.RC_RULE
        )!!
        assertEquals(1, table.rules.size)
        assertEquals("ok", table.rules.single().profile.id)
    }

    @Test
    fun profileJsonRoundTrip() {
        val p = HomeProfile.AOSP.copy(id = "t", drawerMode = DrawerMode.PAGED, drawerRows = 6, drawerSearchPosition = DrawerSearchPosition.BOTTOM, iconScale = 0.85f, source = ProfileSource.BUILT_IN)
        val back = HomeProfile.fromJson(JSONObject(p.toJson().toString()), source = ProfileSource.BUILT_IN)
        assertNotNull(back)
        assertEquals(p, back)
    }

    @Test
    fun overlayReplacesOnlyStatedNumbers() {
        val base = builtIn.select(fp(homePackage = "com.sec.android.app.launcher"))!!
        val over = base.overlay(HomeProfile.Numbers(homeCols = 5, drawerCols = null))
        assertEquals(5, over.homeCols)
        assertEquals(base.homeRows, over.homeRows)
        assertEquals(base.drawerCols, over.drawerCols)
        assertEquals(DrawerMode.PAGED, over.drawerMode)
        assertEquals(ProfileSource.APK, over.source)
        assertEquals(base, base.overlay(HomeProfile.Numbers()))
    }

    // --- ColorOS / OxygenOS -------------------------------------------------------------------

    /** Through V14 the drawer's search bar is at the top; from V15 ColorOS moved it to the bottom. */
    @Test
    fun colorOsSearchMovesToTheBottomFromV15() {
        val v14 = builtIn.select(fp(homePackage = "com.android.launcher", manufacturer = "oneplus", osVersion = "V14.0.0"))!!
        assertEquals(DrawerSearchPosition.TOP, v14.drawerSearchPosition)
        val v16 = builtIn.select(fp(homePackage = "com.android.launcher", manufacturer = "oneplus", osVersion = "V16.0.0"))!!
        assertEquals(DrawerSearchPosition.BOTTOM, v16.drawerSearchPosition)
        // The grid is the same either way - only the bar moved.
        assertEquals(v14.homeCols, v16.homeCols)
        assertEquals(v14.homeRows, v16.homeRows)
        // A build that states its major alone still lands on the V15+ row.
        val v15 = builtIn.select(fp(homePackage = "com.android.launcher", manufacturer = "oppo", osVersion = "V15"))!!
        assertEquals(DrawerSearchPosition.BOTTOM, v15.drawerSearchPosition)
    }

    /** The version split must not reach any other skin: One UI keeps its own row whatever it reads. */
    @Test
    fun colorOsRowsLeaveOtherSkinsAlone() {
        val oneUi = builtIn.select(fp(homePackage = "com.sec.android.app.launcher", manufacturer = "samsung", osVersion = "70000"))!!
        assertEquals("samsung_phone_oneui7", oneUi.id)
        assertEquals(DrawerSearchPosition.BOTTOM, oneUi.drawerSearchPosition)
        assertEquals(DrawerMode.PAGED, oneUi.drawerMode)
        val oneUi6 = builtIn.select(fp(homePackage = "com.sec.android.app.launcher", manufacturer = "samsung", osVersion = "60101"))!!
        assertEquals("samsung_phone", oneUi6.id)
        assertEquals(DrawerSearchPosition.TOP, oneUi6.drawerSearchPosition)
        val miui = builtIn.select(fp(homePackage = "com.miui.home", manufacturer = "xiaomi", osVersion = "OS2.0"))!!
        assertEquals(DrawerMode.NONE, miui.drawerMode)
        assertEquals(DrawerSearchPosition.TOP, miui.drawerSearchPosition)
    }

    /** ColorOS states its layout as a number of its own: 0 home-only, 2 home + drawer, 3 big-icon home. */
    @Test
    fun colorOsLauncherModeMapsOntoOurDrawerModes() {
        val values = JSONObject(ApkProfileReader.DEFAULT_PROBES_JSON)
            .getJSONObject("com.android.launcher")
            .getJSONObject(ApkProfileReader.KEY_DRAWER_MODE_VALUES)
        assertEquals(DrawerMode.NONE, ApkProfileReader.drawerModeOf(0, values))
        assertEquals(DrawerMode.VERTICAL, ApkProfileReader.drawerModeOf(2, values))
        assertEquals(DrawerMode.NONE, ApkProfileReader.drawerModeOf(3, values))
        // 1 is not a mode - LauncherMode.create reads anything unlisted as Standard, and we simply
        // learn nothing and keep the rules-table row.
        assertNull(ApkProfileReader.drawerModeOf(1, values))
        assertNull(ApkProfileReader.drawerModeOf(2, null))
    }

    /** Only launchers whose probe entry names the resource may have their mode read at all. */
    @Test
    fun onlyColorOsLaunchersDeclareADrawerModeProbe() {
        val probes = JSONObject(ApkProfileReader.DEFAULT_PROBES_JSON)
        val withMode = probes.keys().asSequence()
            .filter { probes.optJSONObject(it)?.has(ApkProfileReader.KEY_DRAWER_MODE) == true }
            .toSet()
        assertEquals(
            setOf("com.android.launcher", "com.oppo.launcher", "net.oneplus.launcher", "com.oneplus.launcher"),
            withMode,
        )
    }

    /** A drawer mode read from the launcher's own build wins over the table row and is tagged as such. */
    @Test
    fun overlayTakesTheDrawerModeFromTheApk() {
        val base = builtIn.select(fp(homePackage = "com.android.launcher", manufacturer = "oneplus", osVersion = "V16.0.0"))!!
        val over = base.overlay(HomeProfile.Numbers(drawerMode = DrawerMode.NONE))
        assertEquals(DrawerMode.NONE, over.drawerMode)
        assertEquals(ProfileSource.APK, over.sourceOf(HomeProfile.KEY_DRAWER_MODE))
        // Nothing stated, nothing moved.
        assertEquals(base.drawerMode, base.overlay(HomeProfile.Numbers(homeCols = 4)).drawerMode)
        assertEquals(ProfileSource.BUILT_IN, base.overlay(HomeProfile.Numbers(homeCols = 4)).sourceOf(HomeProfile.KEY_DRAWER_MODE))
    }

    // --- our default setup ---------------------------------------------------------------------

    /** Our own layout is the same everywhere: 4x6 above a four-slot dock, a vertical drawer, our sizes. */
    @Test
    fun defaultSetupIsFourBySixWithADrawer() {
        val d = HomeProfileApplier.DEFAULT_SETUP
        assertEquals(4, d.homeCols)
        assertEquals(6, d.homeRows)
        assertEquals(4, d.dockSize)
        assertEquals(4, d.drawerCols)
        assertEquals(DrawerMode.VERTICAL, d.drawerMode)
        assertEquals(DrawerSearchPosition.BOTTOM, d.drawerSearchPosition)
        // One size everywhere: pages, dock and drawer draw the same icon.
        assertEquals(HomeProfileApplier.DEFAULT_ICON_DP, d.homeArtDp)
        assertEquals(HomeProfileApplier.DEFAULT_ICON_DP, d.dockArtDp)
        assertEquals(HomeProfileApplier.DEFAULT_ICON_DP, d.drawerArtDp)
        assertEquals(HomeProfileApplier.STYLE_OS, HomeProfileApplier.styleFor(osStyle = true))
        assertEquals(HomeProfileApplier.STYLE_DEFAULT, HomeProfileApplier.styleFor(osStyle = false))
    }

    /** Overflow shares the pages it needs; one full page and a page holding two is what we avoid. */
    @Test
    fun overflowSharesItsPagesEvenly() {
        // 26 icons over two 24-cell pages: 13 and 13, not 24 and 2.
        assertEquals(13, HomeAppsFiller.perPage(count = 26, capacity = 24, pages = 2))
        // What fits on one page stays on one page.
        assertEquals(20, HomeAppsFiller.perPage(count = 20, capacity = 24, pages = 1))
        // Never more than the page holds, never less than one icon.
        assertEquals(24, HomeAppsFiller.perPage(count = 100, capacity = 24, pages = 1))
        assertEquals(1, HomeAppsFiller.perPage(count = 0, capacity = 24, pages = 3))
        assertEquals(24, HomeAppsFiller.perPage(count = 10, capacity = 24, pages = 0))
    }

    /** The workspace is the whole list only where there is no drawer to be the list instead. */
    @Test
    fun onlyADrawerlessHomeCarriesEveryApp() {
        val miui = builtIn.select(fp(homePackage = "com.miui.home", manufacturer = "xiaomi"))!!
        assertEquals(DrawerMode.NONE, miui.drawerMode)
        val samsung = builtIn.select(fp(homePackage = "com.sec.android.app.launcher", manufacturer = "samsung", osVersion = "80500"))!!
        assertEquals(DrawerMode.PAGED, samsung.drawerMode)
        val coloros = builtIn.select(fp(homePackage = "com.android.launcher", manufacturer = "oneplus", osVersion = "V16.0.0"))!!
        assertEquals(DrawerMode.VERTICAL, coloros.drawerMode)
        // The mode read from the launcher's own build overrides the row's.
        assertEquals(DrawerMode.NONE, samsung.overlay(HomeProfile.Numbers(drawerMode = DrawerMode.NONE)).drawerMode)
        assertEquals(DrawerMode.VERTICAL, miui.overlay(HomeProfile.Numbers(drawerMode = DrawerMode.VERTICAL)).drawerMode)
    }

    @Test
    fun fingerprintJsonRoundTrip() {
        val f = fp(homePackage = "com.miui.home", versionCode = 42, manufacturer = "xiaomi", model = "2201123G", swDp = 392, osVersion = "OS2.0", device = "malachite")
        assertEquals(f, LauncherFingerprint.fromJson(f.toJson()))
        assertNull(LauncherFingerprint.fromJson("nope"))
    }
}
