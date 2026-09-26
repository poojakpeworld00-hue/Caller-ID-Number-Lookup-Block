package io.launcher.home.interfaces

import io.launcher.home.models.AppWidget

interface WidgetsFragmentListener {
    fun onWidgetLongPressed(appWidget: AppWidget)
}
