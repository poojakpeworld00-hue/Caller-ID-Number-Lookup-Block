package com.calleridapp.numberlookup.launcher.models

import android.appwidget.AppWidgetProviderInfo
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.drawable.Drawable
import com.calleridapp.numberlookup.launcher.helpers.ITEM_TYPE_ICON

// grid cells are from 0-5 by default. Icons and shortcuts occupy 1 slot only, widgets can be bigger
data class HomeScreenGridItem(
    var id: Long?,
    var left: Int,
    var top: Int,
    var right: Int,
    var bottom: Int,
    var page: Int,
    var packageName: String,
    var activityName: String,   // needed at apps that create multiple icons at install, not just the launcher
    var title: String,
    var type: Int,
    var className: String,
    var widgetId: Int,
    var shortcutId: String,   // used at pinned shortcuts at startLauncher call
    var icon: Bitmap? = null,        // store images of pinned shortcuts, those cannot be retrieved after creating
    var docked: Boolean = false,   // special flag, meaning that page, top and bottom don't matter for this item, it is always at the bottom of the screen
    var parentId: Long? = null, // id of folder this item is in (if it is in any)

    var drawable: Drawable? = null,
    var providerInfo: AppWidgetProviderInfo? = null,    // used at widgets
    var activityInfo: ActivityInfo? = null,             // used at shortcuts
    var widthCells: Int = 1,
    var heightCells: Int = 1
) {
    companion object {
        const val FOLDER_MAX_CAPACITY = 16
    }

    constructor() : this(null, -1, -1, -1, -1, 0, "", "", "", ITEM_TYPE_ICON, "", -1, "", null, false, null, null, null, null, 1, 1)

    fun getWidthInCells() = if (right == -1 || left == -1) {
        widthCells
    } else {
        right - left + 1
    }

    fun getHeightInCells() = if (bottom == -1 || top == -1) {
        heightCells
    } else {
        bottom - top + 1
    }

    fun getDockAdjustedTop(rowCount: Int): Int {
        return if (!docked) {
            top
        } else {
            rowCount - 1
        }
    }

    fun getDockAdjustedBottom(rowCount: Int): Int {
        return if (!docked) {
            bottom
        } else {
            rowCount - 1
        }
    }

    fun getItemIdentifier() = "$packageName/$activityName"

    fun getTopLeft(rowCount: Int) = Point(left, getDockAdjustedTop(rowCount))
}
