package com.calleridapp.numberlookup.data

import android.content.Context
import com.calleridapp.admesh.data.getLocationFromIP
import com.calleridapp.numberlookup.BuildConfig
import com.calleridapp.numberlookup.ui.lookup.Territories
import com.calleridapp.numberlookup.util.GuardRail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single source of truth for the user's IP-resolved country.
 *
 * The country is detected **once** — ADDashboardActivity resolves it early via
 * [getLocationFromIP] and stores it in [VaultRegistry.homeCountryIso]. Every other
 * caller (Language, Home, Lookup) goes through [detectCountry], which reuses that
 * cached value and only touches the network if nothing has resolved it yet — so
 * the geo endpoint is never hit multiple times.
 *
 * Best-effort: returns null when there's no cache and the network call fails.
 * ip-api returns only the ISO code, so the dialing code is derived from [Territories].
 */
object RegionLocator {

    private const val TAG = "RegionLocator"

    /**
     * TEST ONLY — force a country in debug builds, bypassing cache and network.
     * Set to an ISO-3166 alpha-2 code (e.g. "JP" for Japan) to test that region's
     * suggestions; set back to null for normal IP detection. No effect in release.
     */
    private val DEBUG_FORCE_ISO: String? = "JP"

    /** Resolved country: ISO-3166 alpha-2 plus the dialing code (digits, no '+'). */
    data class GeoCountry(val iso: String, val dial: String)

    /**
     * The user's country, cache-first:
     *  1. [VaultRegistry.homeCountryIso] — user pick or ADDashboardActivity's detection.
     *  2. [VaultRegistry.geoCountryIso]  — our own previously-cached IP result.
     *  3. Only if both are empty, hit the network once and cache the result.
     *
     * Returns null only when there's no cache and the IP lookup fails.
     */
    suspend fun detectCountry(context: Context): GeoCountry? {
        // Debug override wins over everything so a forced region is deterministic.
        if (BuildConfig.DEBUG) {
            validCountry(DEBUG_FORCE_ISO)?.let { forced ->
                GuardRail.log(TAG, "DEBUG force country=$forced (cache/network bypassed)")
                return GeoCountry(forced, Territories.dialOf(forced).orEmpty())
            }
        }

        val prefs = VaultRegistry(context)

        // Reuse a cached value only if it's a *real* ISO-2 country (present in the
        // dial table). This rejects stale/garbage codes (e.g. a language tag like
        // "JA" wrongly stored as a country), so the cache self-heals via re-detect.
        val cached = validCountry(prefs.homeCountryIso) ?: validCountry(prefs.geoCountryIso)
        if (cached != null) {
            GuardRail.log(TAG, "reuse cached country=$cached (no IP call)")
            return GeoCountry(cached, Territories.dialOf(cached).orEmpty())
        }

        val geo = detectCountryFromIp() ?: return null
        // Cache under our own key (not homeCountryIso) so it's never confused with
        // an explicit user pick — subsequent callers reuse it without a network hit.
        prefs.geoCountryIso = geo.iso
        GuardRail.log(TAG, "cached detected country=${geo.iso} for app-wide reuse")
        return geo
    }

    /** An uppercase ISO-2 code, or null when [raw] isn't a recognised country. */
    private fun validCountry(raw: String?): String? {
        val iso = raw?.takeIf { it.length == 2 }?.uppercase() ?: return null
        return iso.takeIf { Territories.dialOf(it) != null }
    }

    /** One-shot IP lookup via the app's shared [getLocationFromIP] source (ip-api.com). */
    private suspend fun detectCountryFromIp(): GeoCountry? = withContext(Dispatchers.IO) {
        GuardRail.log(TAG, "no cache → getLocationFromIP()")
        val location = getLocationFromIP()
        if (location == null) {
            GuardRail.log(TAG, "getLocationFromIP() returned null → null")
            return@withContext null
        }
        GuardRail.log(TAG, "location: country=${location.country} code=${location.countryCode}")

        val iso = location.countryCode?.takeIf { it.length == 2 }?.uppercase()
        if (iso == null) {
            GuardRail.log(TAG, "no valid countryCode → null")
            return@withContext null
        }
        val dial = Territories.dialOf(iso).orEmpty()
        GuardRail.log(TAG, "resolved country=$iso dial=$dial")
        GeoCountry(iso, dial)
    }
}
