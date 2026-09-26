package com.calleridapp.numberlookup.ui.pkgresult

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.io.FileOutputStream

/**
 * Remembers each app's label, version and icon **while it is still installed**, so the uninstall
 * result screen can show them after the package is gone.
 *
 * A removed package cannot be queried: by the time `ACTION_PACKAGE_REMOVED` arrives the app is
 * already uninstalled and the PackageManager knows nothing about it. So [remember] caches one app,
 * [seedAll] caches every installed launchable app once (and prunes ones that have gone), and [read]
 * returns whatever was cached.
 *
 * Label and version live in a small prefs file; the icon is a downscaled PNG under `pkg_icons/`.
 */
object PackageMetadataCache {

    private const val PREFS = "package_meta"
    private const val ICON_DIR = "pkg_icons"
    private const val KEY_LABEL = "label_"
    private const val KEY_VERSION = "version_"

    data class Meta(val label: String, val version: String, val iconFile: File?)

    /** Caches [pkg]'s label, version and icon. Meant for a background thread; never throws out. */
    fun remember(context: Context, pkg: String) {
        val app = context.applicationContext
        if (pkg.isBlank() || pkg == app.packageName) return
        runCatching {
            val pm = app.packageManager
            val info = packageInfo(pm, pkg) ?: return
            val appInfo = info.applicationInfo ?: return
            settings(app).edit {
                putString(KEY_LABEL + pkg, pm.getApplicationLabel(appInfo).toString())
                putString(KEY_VERSION + pkg, info.versionName.orEmpty())
            }
            saveIcon(app, pkg, pm.getApplicationIcon(appInfo))
        }
    }

    /** The cached metadata for [pkg], or null if nothing was cached before it was removed. */
    fun read(context: Context, pkg: String): Meta? {
        val app = context.applicationContext
        val data = settings(app)
        val label = data.getString(KEY_LABEL + pkg, null) ?: return null
        val version = data.getString(KEY_VERSION + pkg, "").orEmpty()
        return Meta(label, version, iconFile(app, pkg).takeIf { it.exists() })
    }

    /**
     * Caches every currently-installed launchable app that is not cached yet and prunes entries for
     * apps that are no longer installed. Runs once per launch on a background thread — the
     * per-package "already cached" skip keeps every run after the first cheap.
     */
    fun seedAll(context: Context) {
        val app = context.applicationContext
        runCatching {
            val pm = app.packageManager
            val installed = installedPackages(pm)
            val data = settings(app)

            for (pkg in installed) {
                if (pkg == app.packageName) continue
                if (data.contains(KEY_LABEL + pkg)) continue
                if (pm.getLaunchIntentForPackage(pkg) == null) continue
                remember(app, pkg)
            }
            prune(app, installed.toHashSet())
        }
    }

    /** Drops the cache for [pkg] — its icon file and prefs entries. */
    fun forget(context: Context, pkg: String) {
        val app = context.applicationContext
        runCatching {
            settings(app).edit { remove(KEY_LABEL + pkg); remove(KEY_VERSION + pkg) }
            iconFile(app, pkg).delete()
        }
    }

    private fun prune(context: Context, installed: Set<String>) {
        val data = settings(context)
        val stale = data.all.keys
            .filter { it.startsWith(KEY_LABEL) }
            .map { it.removePrefix(KEY_LABEL) }
            .filter { it !in installed }
        if (stale.isEmpty()) return
        data.edit {
            for (pkg in stale) {
                remove(KEY_LABEL + pkg)
                remove(KEY_VERSION + pkg)
            }
        }
        stale.forEach { iconFile(context, it).delete() }
    }

    private fun saveIcon(context: Context, pkg: String, drawable: Drawable) {
        val size = (48 * context.resources.displayMetrics.density).toInt().coerceIn(96, 192)
        val bitmap = drawable.toBitmap(size, size)
        val file = iconFile(context, pkg)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun iconFile(context: Context, pkg: String) =
        File(File(context.filesDir, ICON_DIR), "$pkg.png")

    private fun settings(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun packageInfo(pm: PackageManager, pkg: String) = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }
    }.getOrNull()

    private fun installedPackages(pm: PackageManager): List<String> {
        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
        return apps.map(ApplicationInfo::packageName)
    }
}
