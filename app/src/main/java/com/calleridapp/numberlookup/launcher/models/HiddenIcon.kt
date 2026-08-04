package com.calleridapp.numberlookup.launcher.models

import android.graphics.drawable.Drawable

data class HiddenIcon(
    var id: Long?,
    var packageName: String,
    var activityName: String,
    var title: String,

    var drawable: Drawable? = null,
) {
    constructor() : this(null, "", "", "", null)

    fun getIconIdentifier() = "$packageName/$activityName"
}
