package io.launcher.home.interfaces

import io.launcher.home.models.AppLauncher

interface AllAppsListener {
    fun onAppLauncherLongPressed(x: Float, y: Float, appLauncher: AppLauncher)
}
