package io.launcher.home.profile

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import io.launcher.home.extensions.launcherConfig
import org.fossify.commons.helpers.ensureBackgroundThread
import org.json.JSONObject
import timber.log.Timber

/**
 * What the device and the launcher it shipped with look like — every field read from the
 * system, none guessed. Captured once, before this app takes the Home role, because
 * afterwards the system reports *us* as the default home and the launcher we replaced can
 * only be inferred (see [previousHome]). Stays in our prefs; never leaves the device.
 */
data class LauncherFingerprint(
    /** The launcher we replaced; empty when nothing could be told apart from ourselves. */
    val homePackage: String,
    val homeVersionCode: Long,
    val homeVersionName: String,
    /** Lower-cased, the way rules compare them. */
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val sdk: Int,
    /** `Configuration.smallestScreenWidthDp` — phone < 600, foldable inner / small tablet < 720, tablet after. */
    val swDp: Int,
    /** `Settings.Secure.navigation_mode`: 0 three-button, 1 two-button, 2 gesture, -1 unknown. */
    val navMode: Int,
    /** Portrait display size in dp - the short and the long side - and its density bucket. */
    val widthDp: Int = 0,
    val heightDp: Int = 0,
    val densityDpi: Int = 0,
    val product: String = "",
    /**
     * The OEM skin's own version, read from its system property (One UI `ro.build.version.oneui`,
     * MIUI / HyperOS, ColorOS, Funtouch / OriginOS, EMUI, MagicOS, HiOS) - "60101" on One UI 6.1.1,
     * "V14.0.0" on ColorOS 14. Empty when no known property is set. See [OemOsVersion].
     */
    val osVersion: String = "",
) {
    val isTablet: Boolean get() = swDp >= 720
    val isFoldInner: Boolean get() = swDp in 600..719

    /** [osVersion] as a comparable number - see [OemOsVersion.toNumber]; 0 when unknown. */
    val osVersionNumber: Long get() = OemOsVersion.toNumber(osVersion)

    fun toJson(): String = JSONObject().apply {
        put(KEY_HOME_PACKAGE, homePackage)
        put(KEY_HOME_VERSION_CODE, homeVersionCode)
        put(KEY_HOME_VERSION_NAME, homeVersionName)
        put(KEY_MANUFACTURER, manufacturer)
        put(KEY_BRAND, brand)
        put(KEY_MODEL, model)
        put(KEY_DEVICE, device)
        put(KEY_SDK, sdk)
        put(KEY_SW_DP, swDp)
        put(KEY_NAV_MODE, navMode)
        put(KEY_WIDTH_DP, widthDp)
        put(KEY_HEIGHT_DP, heightDp)
        put(KEY_DENSITY_DPI, densityDpi)
        put(KEY_PRODUCT, product)
        put(KEY_OS_VERSION, osVersion)
    }.toString()

    companion object {
        private const val KEY_HOME_PACKAGE = "home_package"
        private const val KEY_HOME_VERSION_CODE = "home_version_code"
        private const val KEY_HOME_VERSION_NAME = "home_version_name"
        private const val KEY_MANUFACTURER = "manufacturer"
        private const val KEY_BRAND = "brand"
        private const val KEY_MODEL = "model"
        private const val KEY_DEVICE = "device"
        private const val KEY_SDK = "sdk"
        private const val KEY_SW_DP = "sw_dp"
        private const val KEY_NAV_MODE = "nav_mode"
        private const val KEY_WIDTH_DP = "width_dp"
        private const val KEY_HEIGHT_DP = "height_dp"
        private const val KEY_DENSITY_DPI = "density_dpi"
        private const val KEY_PRODUCT = "product"
        private const val KEY_OS_VERSION = "os_version"

        /** The resolver's own package when no default home is set — never "the previous launcher". */
        private const val SYSTEM_RESOLVER = "android"

        fun fromJson(raw: String): LauncherFingerprint? = runCatching {
            val o = JSONObject(raw)
            LauncherFingerprint(
                homePackage = o.optString(KEY_HOME_PACKAGE),
                homeVersionCode = o.optLong(KEY_HOME_VERSION_CODE),
                homeVersionName = o.optString(KEY_HOME_VERSION_NAME),
                manufacturer = o.optString(KEY_MANUFACTURER),
                brand = o.optString(KEY_BRAND),
                model = o.optString(KEY_MODEL),
                device = o.optString(KEY_DEVICE),
                sdk = o.optInt(KEY_SDK),
                swDp = o.optInt(KEY_SW_DP),
                navMode = o.optInt(KEY_NAV_MODE, -1),
                widthDp = o.optInt(KEY_WIDTH_DP),
                heightDp = o.optInt(KEY_HEIGHT_DP),
                densityDpi = o.optInt(KEY_DENSITY_DPI),
                product = o.optString(KEY_PRODUCT),
                osVersion = o.optString(KEY_OS_VERSION),
            )
        }.getOrNull()

        /** The stored fingerprint, or null when none has been captured yet. */
        fun stored(context: Context): LauncherFingerprint? =
            context.launcherConfig.homeFingerprintJson.takeIf { it.isNotEmpty() }?.let(::fromJson)

        /**
         * Captures and stores on a background thread unless a capture that identified the previous
         * launcher is already held. Cheap and idempotent, so it is called from every surface that
         * precedes the Home-role request — whichever one the user reaches first wins.
         */
        fun ensureCaptured(context: Context) {
            val app = context.applicationContext
            val held = stored(app)
            if (held != null && held.homePackage.isNotEmpty()) return
            ensureBackgroundThread {
                runCatching { captureAndStore(app) }
                    .onFailure { Timber.w(it, "LauncherFingerprint: capture failed") }
            }
        }

        /** Synchronous capture + store; returns what was stored. */
        fun captureAndStore(context: Context): LauncherFingerprint {
            val fp = capture(context)
            context.launcherConfig.homeFingerprintJson = fp.toJson()
            return fp
        }

        fun capture(context: Context): LauncherFingerprint {
            val pm = context.packageManager
            val home = previousHome(context, pm)
            val info = home?.let { pkg ->
                runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
            }
            @Suppress("DEPRECATION")
            val versionCode = info?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else it.versionCode.toLong()
            } ?: 0L
            val navMode = runCatching {
                Settings.Secure.getInt(context.contentResolver, "navigation_mode")
            }.getOrDefault(-1)
            val metrics = context.resources.displayMetrics
            val shortPx = minOf(metrics.widthPixels, metrics.heightPixels)
            val longPx = maxOf(metrics.widthPixels, metrics.heightPixels)
            return LauncherFingerprint(
                homePackage = home.orEmpty(),
                homeVersionCode = versionCode,
                homeVersionName = info?.versionName.orEmpty(),
                manufacturer = Build.MANUFACTURER.orEmpty().lowercase(),
                brand = Build.BRAND.orEmpty().lowercase(),
                model = Build.MODEL.orEmpty(),
                device = Build.DEVICE.orEmpty(),
                sdk = Build.VERSION.SDK_INT,
                swDp = context.resources.configuration.smallestScreenWidthDp,
                navMode = navMode,
                widthDp = (shortPx / metrics.density).toInt(),
                heightDp = (longPx / metrics.density).toInt(),
                densityDpi = metrics.densityDpi,
                product = Build.PRODUCT.orEmpty(),
                osVersion = OemOsVersion.read(),
            )
        }

        /**
         * The launcher the user had. The current default home when that is not us; otherwise (we
         * already hold the role, or none is set) the best other candidate among the installed home
         * apps: a package the profile tables know, then any system-image launcher, then whatever is
         * left. Needs the HOME intent in the manifest `<queries>` on API 30+.
         */
        private fun previousHome(context: Context, pm: PackageManager): String? {
            val ours = context.packageName
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val current = runCatching {
                pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            }.getOrNull()
            if (!current.isNullOrEmpty() && current != ours && current != SYSTEM_RESOLVER) return current

            val candidates = runCatching { pm.queryIntentActivities(homeIntent, 0) }.getOrDefault(emptyList())
                .mapNotNull { it.activityInfo?.applicationInfo }
                .filter { it.packageName != ours && it.packageName != SYSTEM_RESOLVER }
                .distinctBy { it.packageName }
            return candidates.maxByOrNull { info ->
                var score = 0
                if (HomeProfileRules.isKnownLauncher(info.packageName)) score += 2
                if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) score += 1
                score
            }?.packageName
        }
    }
}
