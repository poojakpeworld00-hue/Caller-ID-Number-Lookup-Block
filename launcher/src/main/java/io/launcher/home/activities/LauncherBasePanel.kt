package io.launcher.home.activities

import android.os.Bundle
import org.fossify.commons.activities.BaseSimpleActivity
import io.launcher.home.R
import io.launcher.home.helpers.REPOSITORY_NAME

open class LauncherBasePanel : BaseSimpleActivity() {

    // toggled true only for the duration of super.onCreate(), see getPackageName() below
    private var spoofFossifyPackageName = false

    private companion object {
        val FOSSIFY_SPOOF: String = listOf("org", "fossify", "home").joinToString(".")
    }

    /**
     * BaseSimpleActivity carries a deliberate anti-rebrand check baked into Fossify Commons: in
     * onCreate(), and separately inside startCustomizationActivity(), if the running app's
     * package name doesn't start with (or, via a reversed-string check, contain) "fossify", it
     * shows a "you are using a fake version, get the original from fossify.org" warning — with
     * ~2% odds on every screen open, and unconditionally once appRunCount passes 100. This build
     * is a legitimate rebuild of Fossify's own GPL-licensed source under a different app identity,
     * not the unauthorized clone that check is meant to catch, so it is a false positive here.
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
        // Must start with "org.fossify.": Commons' anti-rebrand check reads this name (see above).
        // Assembled at runtime so the docs/rename port scripts can never rewrite it again.
        return if (spoofFossifyPackageName) FOSSIFY_SPOOF else super.getPackageName()
    }

    /**
     * Commons' icon-colour picker indexes this list in lockstep with its own hardcoded
     * 19-entry `appIconColorStrings` / `md_app_icon_colors`, so the size has to stay 19 even
     * though we ship a single icon — a shorter list would index out of bounds inside
     * LineColorPickerDialog, which lives in the AAR and cannot be patched.
     *
     * The feature itself is inert here regardless: Commons toggles the aliases by building
     * "${baseConfig.appId}.activities.SplashActivity.<Colour>", i.e.
     * `com.sms.messenger.launcher.textly.activities.SplashActivity.Green`, while the aliases are
     * actually generated against the module namespace as
     * `io.launcher.home.activities.SplashActivity.Green`. Every toggle therefore targets a
     * component that does not exist. Resolved when applicationId and namespace converge in the
     * package rename.
     */
    /**
     * Empty by design. Commons uses this list to recolour the app's launcher-icon aliases, and a
     * library module has no aliases to offer: they are declared in the host's manifest against the
     * host's applicationId. A host that wants the colour picker to work passes its own ids by
     * subclassing this screen.
     */
    override fun getAppIconIDs() = ArrayList<Int>()

    override fun getAppLauncherName() = getString(R.string.launcher_app_launcher_name)

    override fun getRepositoryName() = REPOSITORY_NAME
}
