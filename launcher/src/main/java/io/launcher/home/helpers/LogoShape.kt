package io.launcher.home.helpers

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

/**
 * Glide transformation that turns a sponsored tile's logo into a launcher icon ([IconShaper.shapeLogo]).
 * Running inside Glide keeps the work on its decode thread and puts the shaped bitmap in its memory
 * cache, so scrolling back to a tile sets the finished icon in the same frame.
 */
class LogoShape(private val resources: Resources, private val tray: IconShaper.Tray) : BitmapTransformation() {

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val out = pool.get(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        IconShaper.shapeLogo(resources, toTransform, tray).apply {
            setBounds(0, 0, outWidth, outHeight)
            draw(Canvas(out))
        }
        return out
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("$ID/${tray.name}".toByteArray(Charsets.UTF_8))
    }

    override fun equals(other: Any?) = other is LogoShape && other.tray == tray

    override fun hashCode() = (ID + tray.name).hashCode()

    private companion object {
        const val ID = "io.launcher.home.helpers.LogoShape.v1"
    }
}
