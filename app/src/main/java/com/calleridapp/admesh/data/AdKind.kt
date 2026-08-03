package com.calleridapp.admesh.data

import kotlin.text.lowercase

enum class AdKind {
    GOOGLE,
    FACEBOOK,
    CUSTOM,
    UNKNOWN;
/**/
    companion object {
        fun fromString(value: String?): AdKind {
            return when (value?.lowercase()) {  // convert input to lowercase
                "google" -> GOOGLE
                "facebook", "fb" -> FACEBOOK
                "custom" -> CUSTOM
                else -> UNKNOWN
            }
        }
    }
}