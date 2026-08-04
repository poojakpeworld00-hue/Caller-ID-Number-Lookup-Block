package com.calleridapp.numberlookup.launcher.widgets

import android.content.Context
import android.graphics.Color

private const val WIDGET_PREFS = "widget_prefs"
private const val DEFAULT_BACKGROUND_COLOR = 0x33000000

internal fun Context.getWidgetBackgroundColor(appWidgetId: Int) =
    getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
        .getInt("widget_${appWidgetId}_bg_color", DEFAULT_BACKGROUND_COLOR)

internal fun Context.getWidgetTextColor(appWidgetId: Int) =
    getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
        .getInt("widget_${appWidgetId}_text_color", Color.WHITE)
