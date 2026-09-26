package io.launcher.home.profile

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * The OEM skin version, off the system property each skin publishes. One `getprop` run, parsed
 * for the first known key that has a value; a skin that publishes none (Pixel, AOSP) reads "".
 * Public tooling, no permission, and never the hidden `SystemProperties` API.
 */
object OemOsVersion {

    /** In priority order: a device sets at most one or two of these. */
    val KEYS = listOf(
        "ro.build.version.oneui",      // Samsung One UI: 60101 = 6.1.1
        "ro.mi.os.version.name",       // Xiaomi HyperOS: OS1.0 / OS2.0
        "ro.miui.ui.version.name",     // MIUI: V14
        "ro.build.version.oplusrom",   // ColorOS / OxygenOS / Realme UI: V14.0.0
        "ro.vivo.os.version",          // Funtouch / OriginOS: 14.0
        "ro.build.version.magic",      // Honor MagicOS: 8.0.0
        "ro.build.version.emui",       // Huawei EMUI: EmotionUI_13.0.0
        "ro.tranos.version",           // Transsion HiOS / XOS / itelOS
        "ro.build.version.incremental",// last resort: the build's own increment
    )

    fun read(): String = runCatching {
        val process = ProcessBuilder("getprop").redirectErrorStream(true).start()
        val values = HashMap<String, String>()
        BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
            lines.forEach { line ->
                // [ro.build.version.oneui]: [60101]
                val key = line.substringAfter('[', "").substringBefore(']', "")
                if (key in KEYS) values[key] = line.substringAfterLast("[", "").substringBefore(']', "").trim()
            }
        }
        process.waitFor(2, TimeUnit.SECONDS)
        KEYS.firstNotNullOfOrNull { key -> values[key]?.takeIf { it.isNotEmpty() } }.orEmpty()
    }.getOrDefault("")

    /**
     * A comparable number for a raw skin version, so a rule can say `os_version_min`. Digits in
     * dotted groups become major*10000 + minor*100 + patch ("V14.0.2" -> 140002, "OS2.0" ->
     * 20000, "14.0" -> 140000); a version that is nothing but digits is kept as is (One UI's
     * "60101" stays 60101, so One UI rules use One UI's own encoding), while a single group
     * carrying a name is that major version ("V15" -> 150000). Anything else is 0.
     */
    fun toNumber(raw: String): Long {
        val groups = Regex("\\d+").findAll(raw).map { it.value }.toList()
        if (groups.isEmpty()) return 0L
        // Only a version that is nothing but digits keeps its own scale (One UI's "60101"); a
        // named one-group version is a major ("V15" -> 150000, same scale as "V15.0.0").
        if (groups.size == 1 && raw.all { it.isDigit() }) return groups[0].toLongOrNull() ?: 0L
        val major = groups.getOrNull(0)?.toLongOrNull() ?: 0L
        val minor = groups.getOrNull(1)?.toLongOrNull() ?: 0L
        val patch = groups.getOrNull(2)?.toLongOrNull() ?: 0L
        return major * 10000 + minor.coerceAtMost(99) * 100 + patch.coerceAtMost(99)
    }
}
