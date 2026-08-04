package com.calleridapp.numberlookup.launcher.interfaces

import com.calleridapp.numberlookup.launcher.models.AppLauncher

interface AllAppsListener {
    fun onAppLauncherLongPressed(x: Float, y: Float, appLauncher: AppLauncher)
}
