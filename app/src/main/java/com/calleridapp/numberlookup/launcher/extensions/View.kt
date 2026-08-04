package com.calleridapp.numberlookup.launcher.extensions

import android.graphics.Color
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.RoundedCorner.POSITION_TOP_LEFT
import android.view.RoundedCorner.POSITION_TOP_RIGHT
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import org.fossify.commons.R
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.helpers.isSPlus
import org.fossify.commons.views.MySearchMenu

fun View.animateScale(
    from: Float,
    to: Float,
    duration: Long,
) = animate()
    .scaleX(to)
    .scaleY(to)
    .setDuration(duration)
    .setInterpolator(AccelerateDecelerateInterpolator())
    .withStartAction {
        scaleX = from
        scaleY = from
    }

fun View.setupDrawerBackground(
    backgroundColor: Int = context.getProperBackgroundColor(),
) {
    background = backgroundColor.toDrawable()

    val insets = rootWindowInsets
    if (isSPlus() && insets != null) {
        val topRightCorner = insets.getRoundedCorner(POSITION_TOP_RIGHT)?.radius ?: 0
        val topLeftCorner = insets.getRoundedCorner(POSITION_TOP_LEFT)?.radius ?: 0
        if (topRightCorner > 0 && topLeftCorner > 0) {
            background = ResourcesCompat.getDrawable(
                context.resources, R.drawable.bottom_sheet_bg, context.theme
            ).apply {
                (this as LayerDrawable)
                    .findDrawableByLayerId(R.id.bottom_sheet_background)
                    .applyColorFilter(backgroundColor)
            }
        }
    }
}

// the drawer sits on a translucent background over the wallpaper, so the search bar is
// forced to the same fixed white-on-translucent skin instead of the theme colors.
// must be called after MySearchMenu.updateColors(), which re-applies the theme tint.
fun MySearchMenu.applyDrawerSkin() {
    setBackgroundColor(Color.TRANSPARENT)
    binding.toolbarContainer.background?.mutate()
        ?.applyColorFilter(context.getColor(com.calleridapp.numberlookup.R.color.all_app_search_bg))
    binding.topToolbarSearchIcon.applyColorFilter(Color.WHITE)
    binding.topToolbarSearch.apply {
        setTextColor(Color.WHITE)
        setHintTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
    }
}
