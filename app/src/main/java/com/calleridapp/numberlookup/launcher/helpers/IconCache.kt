package com.calleridapp.numberlookup.launcher.helpers

import com.calleridapp.numberlookup.launcher.models.AppLauncher

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

    fun clear() {
        launchers = emptyList()
    }
}