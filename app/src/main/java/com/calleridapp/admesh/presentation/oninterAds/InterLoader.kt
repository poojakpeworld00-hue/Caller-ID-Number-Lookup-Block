package com.calleridapp.admesh.presentation.oninterAds

import com.calleridapp.admesh.domain.AdsVault

/**
 * Whether the full-screen "loading ad" spinner shows while an interstitial (or any full-screen ad
 * the launcher loads on demand) is being fetched: Remote Config `inter_loader`.
 *
 * `inter_loader` is the readable key. Until a config carries it, the old `isLoaderForFB` - which
 * despite its name already switched the Google on-demand loader too - keeps deciding, so nothing
 * changes for a config that has not been updated.
 */
object InterLoader {

    const val KEY = "inter_loader"

    fun enabled(pref: AdsVault): Boolean = pref.getBoolean(KEY, pref.getBoolean("isLoaderForFB"))
}
