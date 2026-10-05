package io.launcher.home.helpers

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min

/**
 * Gives every app icon the device's icon shape. An adaptive icon already carries it (the system
 * masks its layers), but an app that still ships a single legacy bitmap is drawn as-is by the
 * framework - a square here, a circle there. Every OEM launcher wraps such icons into an adaptive
 * one (art scaled into the safe zone over a tray) before the mask is applied, so this does the
 * same; which tray is the home profile's call, since One UI uses a white one and Launcher3-based
 * launchers a colour taken from the icon.
 */
object IconShaper {

    /** What sits behind a legacy icon's art once it is wrapped. Profile key `legacy_icon_tray`. */
    enum class Tray { WHITE, DOMINANT;
        companion object {
            fun parse(raw: String?): Tray? = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
        }
    }

    /** The adaptive canvas is 108 dp of which the mask shows the middle 72: a full-bleed legacy square spans exactly that. */
    private const val OPAQUE_INSET = 18f / 108f

    /** Art with its own outline (a circle, a rounded square) sits at ~80% of the visible area, as Launcher3 wraps it. */
    private const val ART_INSET = 25f / 108f

    /** Side of the canvas a drawable is sampled on. */
    private const val SAMPLE_CANVAS = 128

    /** Pixels sampled per axis when reading the legacy bitmap's edge and body colours. */
    private const val SAMPLES = 16

    /** Alpha at or above which a sampled corner counts as painted. */
    private const val OPAQUE_ALPHA = 250

    fun shape(drawable: Drawable, tray: Tray): Drawable = runCatching {
        val shaped = if (drawable is AdaptiveIconDrawable) {
            drawable.withTrayIfClear(tray)
        } else {
            val bitmap = drawable.toSampledBitmap() ?: return drawable
            val opaqueSquare = bitmap.cornersArePainted()
            val background = ColorDrawable(if (opaqueSquare) bitmap.edgeColor() else trayColor(bitmap, tray))
            val foreground = InsetDrawable(drawable, if (opaqueSquare) OPAQUE_INSET else ART_INSET)
            AdaptiveIconDrawable(background, foreground)
        }
        // A wrap that comes out as a plain tray (art that would not draw inside it) is no icon; the
        // original, whatever its shape, beats a blank tile.
        if (shaped !== drawable && !shaped.rendersArt()) drawable else shaped
    }.getOrDefault(drawable)

    /** A store logo bleeds past the mask by this much so its own rounded corners never show inside it. */
    private const val LOGO_INSET = 16f / 108f

    /** Alpha from which a store logo's edge pixel counts as part of the square (they antialias to ~243). */
    private const val LOGO_EDGE_ALPHA = 128

    /**
     * A sponsored tile's logo. Store icons are full-bleed squares, but their outer pixels are
     * antialiased a little below opaque (and some have rounded corners of their own), which [shape]
     * reads as art with its own outline and shrinks onto a tray - a framed tile next to the real
     * apps. A logo whose mid-edges are painted is therefore flattened onto its own edge colour and
     * filled into the mask like an app's opaque legacy icon; anything else gets the usual [shape].
     */
    fun shapeLogo(resources: Resources, logo: Bitmap, tray: Tray): Drawable = runCatching {
        val last = logo.width - 1
        val midX = logo.width / 2
        val midY = logo.height / 2
        val lastY = logo.height - 1
        val square = listOf(midX to 0, 0 to midY, last to midY, midX to lastY)
            .all { (x, y) -> Color.alpha(logo.getPixel(x, y)) >= LOGO_EDGE_ALPHA }
        if (!square) return shape(BitmapDrawable(resources, logo), tray)
        val edge = logo.ringColor()
        val flat = Bitmap.createBitmap(logo.width, logo.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply { drawColor(edge); drawBitmap(logo, 0f, 0f, null) }
        }
        AdaptiveIconDrawable(ColorDrawable(edge), InsetDrawable(BitmapDrawable(resources, flat), LOGO_INSET))
    }.getOrElse { BitmapDrawable(resources, logo) }

    /** Average of the logo's ring a few pixels in, past any antialiased or rounded-off edge. */
    private fun Bitmap.ringColor(): Int {
        val inset = (width / 32).coerceAtLeast(1)
        val lo = inset
        val hi = width - 1 - inset
        val ring = ArrayList<Int>(SAMPLES * 4)
        for (i in 0 until SAMPLES) {
            val p = lo + i * (hi - lo) / (SAMPLES - 1)
            ring += getPixel(p, lo); ring += getPixel(p, hi); ring += getPixel(lo, p); ring += getPixel(hi, p)
        }
        return average(ring) ?: Color.WHITE
    }

    /** True when drawing [this] on the sample canvas leaves more than one colour behind. */
    private fun Drawable.rendersArt(): Boolean {
        val bitmap = toSampledBitmap() ?: return false
        val last = bitmap.width - 1
        val step = (last / (SAMPLES - 1)).coerceAtLeast(1)
        var first: Int? = null
        for (x in 0..last step step) for (y in 0..last step step) {
            val c = bitmap.getPixel(x, y)
            if (Color.alpha(c) < OPAQUE_ALPHA) continue
            val rgb = c and 0xFFFFFF
            if (first == null) first = rgb else if (kotlin.math.abs(Color.red(rgb) - Color.red(first)) + kotlin.math.abs(Color.green(rgb) - Color.green(first)) + kotlin.math.abs(Color.blue(rgb) - Color.blue(first)) > 24) return true
        }
        return false
    }

    /**
     * An adaptive icon whose background layer is missing or see-through shows its art on the bare
     * wallpaper - a circle, a blob. OEM launchers back it with the same tray a legacy icon gets, so
     * the mask has something to fill; one whose background paints the corners is left alone.
     */
    private fun AdaptiveIconDrawable.withTrayIfClear(tray: Tray): Drawable {
        val backgroundPainted = background?.toSampledBitmap()?.cornersArePainted() ?: false
        if (backgroundPainted) return this
        val art = foreground ?: return this
        val artBitmap = art.toSampledBitmap()
        val color = if (artBitmap != null) trayColor(artBitmap, tray) else Color.WHITE
        val ownArt = art.constantState?.newDrawable()?.mutate() ?: art
        return AdaptiveIconDrawable(ColorDrawable(color), ownArt)
    }

    private fun trayColor(bitmap: Bitmap, tray: Tray): Int = when (tray) {
        Tray.WHITE -> Color.WHITE
        // The art's average colour, lifted towards white so the art still reads on it.
        Tray.DOMINANT -> ColorUtils.blendARGB(bitmap.bodyColor(), Color.WHITE, 0.55f)
    }

    /**
     * The drawable on a small square canvas. One without an intrinsic size (a `<color>` background
     * layer is a ColorDrawable) fills whatever bounds it gets, so it is drawn on the default canvas
     * rather than skipped - skipping it would read a solid background as clear.
     */
    private fun Drawable.toSampledBitmap(): Bitmap? {
        val w = intrinsicWidth
        val h = intrinsicHeight
        val size = if (w > 0 && h > 0) min(max(w, h), SAMPLE_CANVAS) else SAMPLE_CANVAS
        return runCatching {
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bmp ->
                val canvas = Canvas(bmp)
                val saved = bounds.let { android.graphics.Rect(it) }
                setBounds(0, 0, size, size)
                draw(canvas)
                bounds = saved
            }
        }.getOrNull()
    }

    /** True when all four corners and the mid-edges are painted: a full-bleed square, no outline of its own. */
    private fun Bitmap.cornersArePainted(): Boolean {
        val last = width - 1
        val mid = width / 2
        val points = listOf(0 to 0, last to 0, 0 to last, last to last, mid to 0, 0 to mid, last to mid, mid to last)
        return points.all { (x, y) -> Color.alpha(getPixel(x, y)) >= OPAQUE_ALPHA }
    }

    /** Average of the outermost ring of pixels - what a seamless tray behind a cropped square must be. */
    private fun Bitmap.edgeColor(): Int {
        val last = width - 1
        val ring = ArrayList<Int>(SAMPLES * 4)
        for (i in 0 until SAMPLES) {
            val p = i * last / (SAMPLES - 1)
            ring += getPixel(p, 0); ring += getPixel(p, last); ring += getPixel(0, p); ring += getPixel(last, p)
        }
        return average(ring) ?: Color.WHITE
    }

    /** Average of the painted pixels on a coarse grid. */
    private fun Bitmap.bodyColor(): Int {
        val last = width - 1
        val body = ArrayList<Int>(SAMPLES * SAMPLES)
        for (i in 0 until SAMPLES) for (j in 0 until SAMPLES) {
            body += getPixel(i * last / (SAMPLES - 1), j * last / (SAMPLES - 1))
        }
        return average(body) ?: Color.WHITE
    }

    private fun average(pixels: List<Int>): Int? {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (c in pixels) {
            if (Color.alpha(c) < OPAQUE_ALPHA) continue
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
        }
        if (n == 0) return null
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}
