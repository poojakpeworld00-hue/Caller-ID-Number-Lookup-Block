package com.calleridapp.numberlookup.launcher.models

import android.graphics.drawable.Drawable
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.helpers.SORT_BY_TITLE
import org.fossify.commons.helpers.SORT_DESCENDING

// the order the app drawer and the side panel list launchers in
val appLauncherComparator = compareBy<AppLauncher>(
    { it.title.normalizeString().lowercase() },
    { it.packageName }
)

data class AppLauncher(
    var id: Long?,
    var title: String,
    var packageName: String,
    var activityName: String,   // some apps create multiple icons, this is needed at clicking them
    var order: Int,
    var thumbnailColor: Int,

    var drawable: Drawable?
) : Comparable<AppLauncher> {

    constructor() : this(null, "", "", "", 0, 0, null)

    companion object {
        var sorting = 0
    }

    override fun equals(other: Any?) = packageName.equals((other as AppLauncher).packageName, true)

    override fun hashCode() = super.hashCode()

    fun getBubbleText() = title

    fun getLauncherIdentifier() = "$packageName/$activityName"

    override fun compareTo(other: AppLauncher): Int {
        var result = when {
            sorting and SORT_BY_TITLE != 0 -> title.normalizeString().lowercase().compareTo(other.title.normalizeString().lowercase())
            else -> {
                if (order > 0 && other.order == 0) {
                    -1
                } else if (order == 0 && other.order > 0) {
                    1
                } else if (order > 0 && other.order > 0) {
                    order.compareTo(other.order)
                } else {
                    title.lowercase().compareTo(other.title.lowercase())
                }
            }
        }

        if (sorting and SORT_DESCENDING != 0) {
            result *= -1
        }

        return result
    }
}
