package com.calleridapp.admesh.data

import kotlin.text.lowercase

enum class AdType {
    GOOGLE,
    FACEBOOK,
    CUSTOM,
    UNKNOWN;
/**/
    companion object {
        fun fromString(value: String?): AdType {
            return when (value?.lowercase()) {  // convert input to lowercase
                "google" -> GOOGLE
                "facebook", "fb" -> FACEBOOK
                "custom" -> CUSTOM
                else -> UNKNOWN
            }
        }
    }
}