package io.launcher.home.api

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.ImageView
import io.launcher.home.R
import io.launcher.home.extensions.launcherConfig
import io.launcher.home.helpers.IconShaper

/**
 * The launcher's icon shape, for icons the host draws itself.
 *
 * Every app icon in the grid, the dock and the drawer goes through [IconShaper]: a legacy
 * single-bitmap icon is wrapped into an adaptive one, so the system mask gives it the device's
 * shape like every other. An icon the host puts on a launcher surface - an app icon inside its own
 * row in the drawer, say - skips that and arrives square, which is the one tile in the grid that
 * does not match. Running it through here gives it the same shape and the same tray.
 *
 * Nothing here knows what the icon is for; it only answers "what would the launcher draw".
 */
object LauncherIcons {

    /** [drawable] shaped as the launcher shapes app icons. Returns it unchanged when it cannot be shaped. */
    @JvmStatic
    fun shape(context: Context, drawable: Drawable): Drawable =
        IconShaper.shape(drawable, context.launcherConfig.legacyIconTray)

    /**
     * Reshapes what [imageView] is showing, in place. Call it after the image is set; an empty view
     * is left alone, and so is one already shaped, so calling it twice is harmless.
     */
    @JvmStatic
    fun applyTo(imageView: ImageView) {
        val current = imageView.drawable ?: return
        if (imageView.getTag(R.id.lnch_tag_icon_shaped) === current) return
        val shaped = shape(imageView.context, current)
        imageView.setImageDrawable(shaped)
        imageView.setTag(R.id.lnch_tag_icon_shaped, shaped)
    }
}
