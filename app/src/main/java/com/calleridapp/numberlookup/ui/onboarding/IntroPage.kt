package com.calleridapp.numberlookup.ui.onboarding

import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import com.calleridapp.numberlookup.R

/**
 * A page shows EITHER a composed [customArtRes] layout (when non-zero) or a
 * simple [artRes] drawable.
 */
data class IntroPage(
    @param:StringRes val titleRes: Int,
    @param:StringRes val descRes: Int,
    @param:DrawableRes val artRes: Int = 0,
    @param:LayoutRes val customArtRes: Int = 0
)

object IntroPages {
    val all: List<IntroPage> = listOf(
        IntroPage(R.string.onboarding_title, R.string.onboarding_desc, customArtRes = R.layout.art_onboarding_caller_view),
        IntroPage(R.string.onboarding_title_2, R.string.onboarding_desc_2, customArtRes = R.layout.art_onboarding_spam_view),
        IntroPage(R.string.onboarding_title_3, R.string.onboarding_desc_3, customArtRes = R.layout.art_onboarding_spam_view1)
    )
}
