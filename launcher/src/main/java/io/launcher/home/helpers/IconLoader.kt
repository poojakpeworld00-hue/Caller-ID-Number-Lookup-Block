package io.launcher.home.helpers

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import io.launcher.home.R
import io.launcher.home.extensions.getDrawableForPackageName
import io.launcher.home.extensions.launcherConfig
import java.util.concurrent.Executors

/**
 * One launcher icon at a time, on demand.
 *
 * [LauncherScan] decodes every installed app before the list is complete; on a cold start that is
 * seconds of coloured placeholder tiles. A tile that is actually on screen does not need to wait
 * for the other hundred: it asks here, gets its own icon in a few milliseconds, and the scan finds
 * it already cached. Same shaping and size as the scan, so the two produce the same picture.
 *
 * The cache must never outlive the icon it holds. An app update installs a new APK whose icon may
 * differ, so the scan drops every entry whose `sourceDir` moved ([invalidate]); a change of the
 * legacy-icon tray re-shapes every icon, so [syncTray] empties it; and [clear] follows
 * [IconCache.clear]. Without those, an updated app kept its old icon until the process died.
 */
object IconLoader {

    /** Sized by bitmap bytes (KB), not entries: an eighth of the heap, whatever the density. */
    private val cache = object : LruCache<String, Drawable>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(1024)
    ) {
        override fun sizeOf(key: String, value: Drawable): Int =
            ((value as? BitmapDrawable)?.bitmap?.allocationByteCount ?: 0) / 1024 + 1
    }
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "icon-loader").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = true }
    }

    @Volatile
    private var tray: IconShaper.Tray? = null

    private fun key(packageName: String, activityName: String) = "$packageName/$activityName"

    fun cached(packageName: String, activityName: String): Drawable? = cache.get(key(packageName, activityName))

    /** Remember an icon decoded elsewhere (the scan), so a tile bound later finds it at once. */
    fun put(packageName: String, activityName: String, drawable: Drawable) {
        cache.put(key(packageName, activityName), drawable)
    }

    /** Forgets the icons for these `package/activity` keys - their APK changed. */
    fun invalidate(keys: Collection<String>) {
        keys.forEach { cache.remove(it) }
    }

    fun clear() {
        cache.evictAll()
    }

    /** Empties the cache when [current] is not the tray its icons were shaped on. */
    fun syncTray(current: IconShaper.Tray) {
        if (tray != current) {
            if (tray != null) clear()
            tray = current
        }
    }

    /** Decodes off the main thread and calls [onReady] on it; [onReady] is skipped when the decode fails. */
    fun load(context: Context, packageName: String, activityName: String, onReady: (Drawable) -> Unit) {
        val app = context.applicationContext
        pool.execute {
            val drawable = cached(packageName, activityName)
                ?: decode(app, packageName, activityName)?.also { put(packageName, activityName, it) }
            if (drawable != null) main.post { onReady(drawable) }
        }
    }

    private fun decode(context: Context, packageName: String, activityName: String): Drawable? = runCatching {
        val pm = context.packageManager
        val legacyTray = context.launcherConfig.legacyIconTray
        syncTray(legacyTray)
        val raw = pm.getActivityInfo(ComponentName(packageName, activityName), 0).loadIcon(pm)
            ?.let { IconShaper.shape(it, legacyTray) }
            ?: context.getDrawableForPackageName(packageName)
            ?: return null
        val side = context.resources.getDimensionPixelSize(R.dimen.launcher_icon_cache_size)
        raw.toBitmap(
            width = raw.intrinsicWidth.coerceIn(1, side),
            height = raw.intrinsicHeight.coerceIn(1, side),
            config = Bitmap.Config.ARGB_8888,
        ).toDrawable(context.resources)
    }.getOrNull()
}
