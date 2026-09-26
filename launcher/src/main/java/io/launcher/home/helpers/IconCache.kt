package io.launcher.home.helpers

import io.launcher.home.models.AppLauncher

object IconCache {
    @Volatile
    private var cachedLaunchers = emptyList<AppLauncher>()

    var launchers: List<AppLauncher>
        get() = cachedLaunchers
        set(value) {
            synchronized(this) {
                cachedLaunchers = value
            }
        }

    /**
     * What the installed set looked like when [launchers] was last built from PackageManager.
     *
     * LauncherPanel.getAllAppLaunchers rebuilds every launcher's icon on every resume — a full-size
     * ARGB_8888 bitmap plus a per-pixel average, per installed app, on every press of Home. That is
     * tens of MB of bitmap churn for an answer that only changes when an app is installed, removed,
     * updated or hidden, and the resulting GC pressure lengthens every main-thread pause.
     *
     * Holding the signature lets that work be skipped when nothing has moved. Null means "unknown",
     * which forces a rebuild — the safe default, and what a cold start and [clear] both leave behind.
     */
    @Volatile
    var installedSignature: String? = null

    /**
     * Each cached launcher's APK path, keyed `package/activity`. A launcher whose path is unchanged
     * keeps its decoded icon when the installed set moves (one app added or removed); an update
     * installs a new APK path, so its icon is decoded again.
     */
    @Volatile
    var sourceDirs: Map<String, String> = emptyMap()

    fun clear() {
        launchers = emptyList()
        installedSignature = null
        sourceDirs = emptyMap()
    }
}