package com.calleridapp.numberlookup.launcher.activities

import android.os.Bundle
import org.fossify.commons.activities.BaseSimpleActivity
import com.calleridapp.numberlookup.R
import com.calleridapp.numberlookup.launcher.helpers.REPOSITORY_NAME

open class SimpleActivity : BaseSimpleActivity() {

    // toggled true only for the duration of super.onCreate(), see getPackageName() below
    private var spoofFossifyPackageName = false

    /**
     * BaseSimpleActivity carries a deliberate anti-rebrand check baked into Fossify Commons: in
     * onCreate(), and separately inside startCustomizationActivity(), if the running app's
     * package name doesn't start with (or, via a reversed-string check, contain) "fossify", it
     * shows a "you are using a fake version, get the original from fossify.org" warning — with
     * ~2% odds on every screen open, and unconditionally once appRunCount passes 100. This build
     * is a legitimate rebuild of Fossify's own GPL-licensed source under a different app identity
     * (com.calleridapp.numberlookup), not the unauthorized clone that check is meant to catch, so
     * it's a false positive here.
     *
     * getPackageName() is spoofed only for the exact windows those checks run in — never anywhere
     * else — so nothing else in the app (content provider authorities, PackageManager
     * self-lookups, permission checks, FileProvider paths, etc.) ever sees anything but the real
     * package name.
     */
    protected fun <T> withFossifyPackageNameSpoofed(block: () -> T): T {
        spoofFossifyPackageName = true
        try {
            return block()
        } finally {
            spoofFossifyPackageName = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        withFossifyPackageNameSpoofed { super.onCreate(savedInstanceState) }
    }

    override fun getPackageName(): String {
        // Must stay a literal "org.fossify.*" string — that prefix is exactly what the check
        // below looks for. It is not this app's package and must not be renamed with it.
        return if (spoofFossifyPackageName) "org.fossify.home" else super.getPackageName()
    }

    /**
     * The colour-customization screen expects one launcher icon per accent colour, each backed by
     * an activity-alias it enables. This app ships a single brand icon and no aliases, so every
     * entry is that one icon — commons already swallows the failed alias toggles, and the picker
     * then just changes the accent colour without ever restyling the home-screen icon.
     */
    override fun getAppIconIDs() = ArrayList(List(APP_ICON_COLOR_COUNT) { R.mipmap.ic_launcher })

    override fun getAppLauncherName() = getString(R.string.app_launcher_name)

    override fun getRepositoryName() = REPOSITORY_NAME

    private companion object {
        /** Matches the number of accent colours commons offers in getAppIconColors(). */
        const val APP_ICON_COLOR_COUNT = 19
    }
}
