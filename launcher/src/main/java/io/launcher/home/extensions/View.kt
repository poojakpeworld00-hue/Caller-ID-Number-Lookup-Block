package io.launcher.home.extensions

import android.graphics.Color
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.RoundedCorner.POSITION_TOP_LEFT
import android.view.RoundedCorner.POSITION_TOP_RIGHT
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
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
    val res = io.launcher.home.R.drawable.lnch_drawer_search_bg
    val dp = resources.displayMetrics.density
    setBackgroundColor(Color.TRANSPARENT)
    // A quiet One UI-style pill: flat translucent white, fully rounded, no shadow. At the bottom of
    // a One UI-styled sheet (launcher_config.os_style) it is compact and centred the way One UI's
    // Finder bar is; everywhere else - our own setup, or at the top - it spans the sheet. setupViews runs from a global-layout hook, so every size
    // below is written only when it differs.
    val pillHeight = (46 * dp).toInt()
    binding.searchBarContainer.apply {
        val height = pillHeight + paddingTop + paddingBottom
        if (layoutParams != null && layoutParams.height != height) layoutParams = layoutParams.apply { this.height = height }
    }
    binding.toolbarContainer.apply {
        background = ContextCompat.getDrawable(context, res)
        clipToOutline = true
        elevation = 0f
        val compact = context.launcherConfig.drawerSearchAtBottom &&
            context.launcherConfig.launcherStyle != io.launcher.home.profile.HomeProfileApplier.STYLE_DEFAULT
        val width = if (compact) {
            (resources.displayMetrics.widthPixels * 0.62f).toInt().coerceAtLeast((240 * dp).toInt())
        } else {
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        }
        (layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { params ->
            if (params.width != width || params.height != pillHeight || params.gravity != android.view.Gravity.CENTER) {
                params.width = width
                params.height = pillHeight
                params.gravity = android.view.Gravity.CENTER
                layoutParams = params
            }
        }
    }
    binding.topToolbarSearchIcon.apply {
        applyColorFilter(context.getColor(io.launcher.home.R.color.launcher_drawer_search_hint))
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        val side = (16 * dp).toInt()
        val vertical = ((pillHeight - 20 * dp) / 2).toInt()
        if (paddingStart != side || paddingTop != vertical) setPaddingRelative(side, vertical, 0, vertical)
        val width = side + (20 * dp).toInt()
        if (layoutParams != null && layoutParams.width != width) layoutParams = layoutParams.apply { this.width = width }
    }
    binding.topToolbarSearch.apply {
        setTextColor(Color.WHITE)
        setHintTextColor(context.getColor(io.launcher.home.R.color.launcher_drawer_search_hint))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        val gap = (10 * dp).toInt()
        if (paddingStart != gap) setPaddingRelative(gap, paddingTop, (16 * dp).toInt(), paddingBottom)
    }
}

/**
 * Insets a full-bleed layout by the system bars and any display cutout.
 *
 * The onboarding and splash screens draw edge to edge behind transparent bars, so without this
 * their chrome — the progress bar and Skip at the top, the CTA at the bottom — sits underneath
 * the status and navigation bars. Padding the root keeps its background full-bleed while the
 * content stays inside the safe area, which is also what the design's own device frame shows.
 */
fun View.padBySystemBars() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val insets = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.updatePadding(
            left = insets.left,
            top = insets.top,
            right = insets.right,
            bottom = insets.bottom
        )
        windowInsets
    }
}
