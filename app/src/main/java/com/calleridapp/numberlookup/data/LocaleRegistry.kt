package com.calleridapp.numberlookup.data

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Applies a per-app language using the AndroidX AppCompat locale APIs.
 * Persistence is handled by AppCompat (see AppLocalesMetadataHolderService in the manifest).
 */
object LocaleRegistry {

    fun apply(languageTag: String) {
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(languageTag)
        )
    }

}
