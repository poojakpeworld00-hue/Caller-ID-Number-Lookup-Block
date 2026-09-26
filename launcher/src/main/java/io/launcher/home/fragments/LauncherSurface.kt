package io.launcher.home.fragments

import android.content.Context
import android.util.AttributeSet
import android.widget.RelativeLayout
import androidx.viewbinding.ViewBinding
import io.launcher.home.activities.LauncherPanel

abstract class LauncherSurface<BINDING : ViewBinding>(
    context: Context,
    attributeSet: AttributeSet
) : RelativeLayout(context, attributeSet) {
    protected var activity: LauncherPanel? = null
    protected lateinit var binding: BINDING

    abstract fun setupFragment(activity: LauncherPanel)
}
