package io.launcher.home.promo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherAdsConfigTest {

    private val blob = """
        {"organic":{"screenWiseAds":{
            "app_drawer": {"reload_ad_on_open": false, "ad_row_position": 9, "adType": "native", "loadType": "preload",
                           "adData": {"adId": "x", "isActive": false, "adSubType": 4}, "adDataList": []},
            "apps_panel": {"reload_ad_on_open": false, "adType": "native"}
         }},
         "marketing":{"screenWiseAds":{
            "app_drawer": {"ad_row_position": 3, "adType": "native"}
         }}}
    """.trimIndent()

    @Test
    fun readsLauncherOnlyFieldsFromTheAudienceBranch() {
        val organic = LauncherAdsConfig.screenWiseAd(blob, organic = true, LauncherAdsConfig.KEY_APP_DRAWER)
        assertEquals(9, organic.optInt("ad_row_position"))
        assertFalse(organic.optBoolean("reload_ad_on_open", true))

        val marketing = LauncherAdsConfig.screenWiseAd(blob, organic = false, LauncherAdsConfig.KEY_APP_DRAWER)
        assertEquals(3, marketing.optInt("ad_row_position"))
        assertTrue(marketing.optBoolean("reload_ad_on_open", true))
    }

    @Test
    fun missingKeyBranchOrGarbageIsEmpty() {
        assertEquals(0, LauncherAdsConfig.screenWiseAd(blob, organic = false, LauncherAdsConfig.KEY_PANEL_BOTTOM).length())
        assertEquals(0, LauncherAdsConfig.screenWiseAd("""{"organic":{}}""", organic = true, "app_drawer").length())
        assertEquals(0, LauncherAdsConfig.screenWiseAd("", organic = true, "app_drawer").length())
        assertEquals(0, LauncherAdsConfig.screenWiseAd("not json", organic = true, "app_drawer").length())
    }

    @Test
    fun flatBlobWithoutAudienceBranchesIsReadAsIs() {
        val flat = """{"screenWiseAds":{"app_drawer":{"ad_row_position":5}}}"""
        assertEquals(5, LauncherAdsConfig.screenWiseAd(flat, organic = true, "app_drawer").optInt("ad_row_position"))
        assertEquals(5, LauncherAdsConfig.screenWiseAd(flat, organic = false, "app_drawer").optInt("ad_row_position"))
    }
}
