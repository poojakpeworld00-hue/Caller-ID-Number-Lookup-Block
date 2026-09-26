package io.launcher.home.profile

import org.json.JSONObject

/** How the app drawer is reached: a vertical scrolling list, Samsung-style horizontal pages, or not at all (every app on the workspace). */
enum class DrawerMode { VERTICAL, PAGED, NONE }

/** Where the drawer's search bar sits - Samsung moved its Finder bar to the bottom with One UI 6.1. */
enum class DrawerSearchPosition { TOP, BOTTOM }

/**
 * Where a value came from - the tiers of the design: the launcher's own APK resources (B), a
 * documented OEM default from Remote Config or the built-in table (C), or our standard
 * configuration when nothing was known (STANDARD).
 */
enum class ProfileSource { APK, RC_RULE, BUILT_IN, STANDARD }

/**
 * The factory look of one launcher build on one device class: what the user saw before we became
 * the home app, expressed in our own knobs. Numbers are the OEM's — [homeRows] counts the rows
 * *above* the dock, the way every launcher advertises its grid ("4×5"), and the applier adds the
 * dock row when it writes `Config.homeRowCount`.
 */
data class HomeProfile(
    val id: String,
    val homeCols: Int,
    val homeRows: Int,
    val dockSize: Int,
    val drawerMode: DrawerMode,
    val drawerCols: Int,
    /** Meaningful for [DrawerMode.PAGED] only. */
    val drawerRows: Int,
    val drawerSearch: Boolean,
    val homeLabels: Boolean,
    val drawerLabels: Boolean,
    val source: ProfileSource,
    val drawerSearchPosition: DrawerSearchPosition = DrawerSearchPosition.TOP,
    /** Icon size relative to ours at the same grid: 1.0 is what the grid draws by itself. Used only when [iconDp] is 0. */
    val iconScale: Float = 1f,
    /** The icon size the launcher we replaced drew on its workspace, in dp; 0 = unknown. Drawn exactly, centred in the cell. */
    val iconDp: Int = 0,
    /** Dock icon in dp; 0 = same as [iconDp]. One UI draws its label-less hotseat larger than the workspace. */
    val dockIconDp: Int = 0,
    /** Drawer icon in dp; 0 = same as [iconDp]. */
    val drawerIconDp: Int = 0,
    /**
     * The same three sizes as a fraction of the display's short side, the way Launcher3-free OEM
     * launchers state them in code (One UI: `width × 0.164`); resolved into the dp fields against
     * this device's width when the dp field is 0. 0 = not stated.
     */
    val iconWidthFraction: Float = 0f,
    val dockIconWidthFraction: Float = 0f,
    val drawerIconWidthFraction: Float = 0f,
    /**
     * [iconWidthFraction] per screen density, for a launcher that picks its icon ratio by screen
     * zoom (One UI's `PhoneItemStyleFactory.getDefaultIconSize`: 0.161 at 420 dpi up to 0.172 at
     * 540). The entry nearest the display's density wins over [iconWidthFraction]. Empty = none.
     */
    val iconWidthFractionByDpi: Map<Int, Float> = emptyMap(),
    /**
     * The share of its stated item size the launcher actually fills with icon art, for a launcher
     * that keeps a margin inside the item. We draw the art edge to edge, so the drawn dp is the item
     * dp times this. 0 = 1.0 (One UI: its width fraction is already the art).
     */
    val iconInset: Float = 0f,
    /** The tray behind a wrapped legacy icon: `white` (One UI) or `dominant` (Launcher3: a colour from the art). */
    val legacyIconTray: String = LEGACY_TRAY_DOMINANT,
    /** App-label text size in sp; 0 = our standard size (nothing known). One UI phones draw 11, Launcher3 states its own `iconTextSize`. */
    val labelSp: Float = 0f,
    /** Per field, which tier supplied it; fields not listed carry [source]. */
    val fieldSources: Map<String, ProfileSource> = emptyMap(),
) {
    /** The grid numbers a launcher's resources can supply; anything null keeps the base profile's value. */
    data class Numbers(
        val homeCols: Int? = null,
        val homeRows: Int? = null,
        val dockSize: Int? = null,
        val drawerCols: Int? = null,
        /** The launcher's workspace icon size in dp (Launcher3 `iconImageSize`). */
        val iconSizeDp: Int? = null,
        /** The launcher's workspace label text size in sp (Launcher3 `iconTextSize`, or a dimen read with its unit). */
        val labelSp: Float? = null,
        /** Drawer icon in dp when the launcher states one apart from the workspace (Launcher3 `allAppsIconSize`). */
        val drawerIconDp: Int? = null,
        /** Dock icon in dp when stated apart (Launcher3 `hotseatIconSize`, where a fork declares it). */
        val dockIconDp: Int? = null,
        /**
         * Whether the launcher we replaced keeps an app drawer, when its own build states it -
         * only launchers whose probe entry says where to read it (ColorOS / OxygenOS today).
         */
        val drawerMode: DrawerMode? = null,
        /** Whether that launcher put each new install on the workspace, when its build states it. */
    ) {
        val isEmpty: Boolean
            get() = homeCols == null && homeRows == null && dockSize == null && drawerCols == null && iconSizeDp == null &&
                labelSp == null && drawerIconDp == null && dockIconDp == null && drawerMode == null

        /** This, with [other] filling whatever this leaves null. */
        fun fill(other: Numbers) = Numbers(
            homeCols = homeCols ?: other.homeCols,
            homeRows = homeRows ?: other.homeRows,
            dockSize = dockSize ?: other.dockSize,
            drawerCols = drawerCols ?: other.drawerCols,
            iconSizeDp = iconSizeDp ?: other.iconSizeDp,
            labelSp = labelSp ?: other.labelSp,
            drawerIconDp = drawerIconDp ?: other.drawerIconDp,
            dockIconDp = dockIconDp ?: other.dockIconDp,
            drawerMode = drawerMode ?: other.drawerMode,
        )
    }

    /**
     * Lays the launcher's own numbers over this profile. [ownIconDp] is the icon size our grid
     * would draw at the resulting column count on this display, so an OEM icon size becomes a
     * scale against it.
     */
    fun overlay(numbers: Numbers, ownIconDp: (cols: Int) -> Float = { 0f }): HomeProfile {
        if (numbers.isEmpty) return this
        val cols = numbers.homeCols ?: homeCols
        val scale = numbers.iconSizeDp?.let { oem ->
            val ours = ownIconDp(cols)
            if (ours > 0f) (oem / ours).coerceIn(MIN_ICON_SCALE, MAX_ICON_SCALE) else null
        }
        // Every field keeps the tier it had before; only what the APK supplied moves to APK.
        val tagged = FIELD_KEYS.associateWith { sourceOf(it) }.toMutableMap()
        if (numbers.homeCols != null) tagged[KEY_HOME_COLS] = ProfileSource.APK
        if (numbers.homeRows != null) tagged[KEY_HOME_ROWS] = ProfileSource.APK
        if (numbers.dockSize != null) tagged[KEY_DOCK_SIZE] = ProfileSource.APK
        if (numbers.drawerCols != null) tagged[KEY_DRAWER_COLS] = ProfileSource.APK
        if (scale != null) tagged[KEY_ICON_SCALE] = ProfileSource.APK
        val label = numbers.labelSp?.takeIf { it in MIN_LABEL_SP..MAX_LABEL_SP }
        if (label != null) tagged[KEY_LABEL_SP] = ProfileSource.APK
        val dp = numbers.iconSizeDp?.takeIf { it in MIN_ICON_DP..MAX_ICON_DP }
        if (dp != null) tagged[KEY_ICON_DP] = ProfileSource.APK
        val dockDp = numbers.dockIconDp?.takeIf { it in MIN_ICON_DP..MAX_ICON_DP }
        if (dockDp != null) tagged[KEY_DOCK_ICON_DP] = ProfileSource.APK
        val drawerDp = numbers.drawerIconDp?.takeIf { it in MIN_ICON_DP..MAX_ICON_DP }
        if (drawerDp != null) tagged[KEY_DRAWER_ICON_DP] = ProfileSource.APK
        if (numbers.drawerMode != null) tagged[KEY_DRAWER_MODE] = ProfileSource.APK
        return copy(
            homeCols = cols,
            homeRows = numbers.homeRows ?: homeRows,
            dockSize = numbers.dockSize ?: dockSize,
            drawerCols = numbers.drawerCols ?: drawerCols,
            iconScale = scale ?: iconScale,
            labelSp = label ?: labelSp,
            iconDp = dp ?: iconDp,
            dockIconDp = dockDp ?: dockIconDp,
            drawerIconDp = drawerDp ?: drawerIconDp,
            drawerMode = numbers.drawerMode ?: drawerMode,
            source = ProfileSource.APK,
            fieldSources = tagged,
        )
    }

    /**
     * The dp fields settled for a display [widthDp] wide. A fraction stated by the profile wins over
     * a dp read from the APK: it is the launcher's own formula (One UI sizes icons off the window
     * width), while its `app_icon_size` dimen is only that formula at the default screen zoom - on a
     * phone zoomed out to 411 dp, 63 dp against the 67 dp One UI really draws. Idempotent.
     */
    fun resolveFractions(widthDp: Int, densityDpi: Int = 0): HomeProfile {
        if (widthDp <= 0) return this
        fun dp(current: Int, fraction: Float) = if (fraction > 0f) Math.round(fraction * widthDp).takeIf { it in MIN_ICON_DP..MAX_ICON_DP } ?: current else current
        val iconFraction = iconFractionFor(densityDpi)
        val resolved = copy(
            iconDp = dp(iconDp, iconFraction),
            dockIconDp = dp(dockIconDp, dockIconWidthFraction),
            drawerIconDp = dp(drawerIconDp, drawerIconWidthFraction),
        )
        // A dp the fraction replaced now carries the fraction's tier, so the resolve log stays true.
        val retagged = fieldSources.toMutableMap()
        fun retag(key: String, fractionKey: String, before: Int, after: Int) {
            if (before != after) retagged[key] = sourceOf(fractionKey)
        }
        retag(KEY_ICON_DP, KEY_ICON_WIDTH_FRACTION, iconDp, resolved.iconDp)
        retag(KEY_DOCK_ICON_DP, KEY_DOCK_ICON_WIDTH_FRACTION, dockIconDp, resolved.dockIconDp)
        retag(KEY_DRAWER_ICON_DP, KEY_DRAWER_ICON_WIDTH_FRACTION, drawerIconDp, resolved.drawerIconDp)
        return resolved.copy(fieldSources = retagged)
    }

    /** The workspace icon fraction at [densityDpi]: the nearest per-density entry, else the flat one. */
    fun iconFractionFor(densityDpi: Int): Float =
        iconWidthFractionByDpi.takeIf { densityDpi > 0 && it.isNotEmpty() }
            ?.minByOrNull { (dpi, _) -> kotlin.math.abs(dpi - densityDpi) }?.value
            ?: iconWidthFraction

    /** The dp each surface draws: the launcher's item size times [iconInset]; dock and drawer fall back to the workspace. 0 = unknown. */
    private fun artDp(item: Int): Int = if (item == 0) 0 else Math.round(item * (if (iconInset > 0f) iconInset else 1f)).coerceIn(MIN_ICON_DP, MAX_ICON_DP)
    val homeArtDp: Int get() = artDp(iconDp)
    val dockArtDp: Int get() = artDp(if (dockIconDp > 0) dockIconDp else iconDp)
    val drawerArtDp: Int get() = artDp(if (drawerIconDp > 0) drawerIconDp else iconDp)

    /** The tier behind one field, for the log. */
    fun sourceOf(key: String): ProfileSource = fieldSources[key] ?: source

    /** One line naming every field and its tier. Local log only; nothing leaves the device. */
    fun describeSources(): String = FIELD_KEYS.joinToString(" ") { "$it=${sourceOf(it).name.lowercase()}" }

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, id)
        put(KEY_HOME_COLS, homeCols)
        put(KEY_HOME_ROWS, homeRows)
        put(KEY_DOCK_SIZE, dockSize)
        put(KEY_DRAWER_MODE, drawerMode.name.lowercase())
        put(KEY_DRAWER_COLS, drawerCols)
        put(KEY_DRAWER_ROWS, drawerRows)
        put(KEY_DRAWER_SEARCH, drawerSearch)
        put(KEY_HOME_LABELS, homeLabels)
        put(KEY_DRAWER_LABELS, drawerLabels)
        put(KEY_DRAWER_SEARCH_POSITION, drawerSearchPosition.name.lowercase())
        put(KEY_ICON_SCALE, iconScale.toDouble())
        put(KEY_LABEL_SP, labelSp.toDouble())
        put(KEY_ICON_DP, iconDp)
        put(KEY_DOCK_ICON_DP, dockIconDp)
        put(KEY_DRAWER_ICON_DP, drawerIconDp)
        put(KEY_ICON_WIDTH_FRACTION, iconWidthFraction.toDouble())
        put(KEY_DOCK_ICON_WIDTH_FRACTION, dockIconWidthFraction.toDouble())
        put(KEY_DRAWER_ICON_WIDTH_FRACTION, drawerIconWidthFraction.toDouble())
        if (iconWidthFractionByDpi.isNotEmpty()) {
            put(KEY_ICON_WIDTH_FRACTION_BY_DPI, JSONObject().apply { iconWidthFractionByDpi.forEach { (dpi, f) -> put(dpi.toString(), f.toDouble()) } })
        }
        put(KEY_ICON_INSET, iconInset.toDouble())
        put(KEY_LEGACY_ICON_TRAY, legacyIconTray)
        put(KEY_SOURCE, source.name.lowercase())
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_HOME_COLS = "home_cols"
        const val KEY_HOME_ROWS = "home_rows"
        const val KEY_DOCK_SIZE = "dock_size"
        const val KEY_DRAWER_MODE = "drawer_mode"
        const val KEY_DRAWER_COLS = "drawer_cols"
        const val KEY_DRAWER_ROWS = "drawer_rows"
        const val KEY_DRAWER_SEARCH = "drawer_search"
        const val KEY_HOME_LABELS = "home_labels"
        const val KEY_DRAWER_LABELS = "drawer_labels"
        const val KEY_DRAWER_SEARCH_POSITION = "drawer_search_position"
        const val KEY_ICON_SCALE = "icon_scale"
        const val KEY_LABEL_SP = "label_sp"
        const val KEY_ICON_DP = "icon_dp"
        const val KEY_DOCK_ICON_DP = "dock_icon_dp"
        const val KEY_DRAWER_ICON_DP = "drawer_icon_dp"
        const val KEY_ICON_WIDTH_FRACTION = "icon_width_fraction"
        const val KEY_ICON_WIDTH_FRACTION_BY_DPI = "icon_width_fraction_by_dpi"
        const val KEY_DOCK_ICON_WIDTH_FRACTION = "dock_icon_width_fraction"
        const val KEY_DRAWER_ICON_WIDTH_FRACTION = "drawer_icon_width_fraction"
        const val KEY_ICON_INSET = "icon_inset"
        const val KEY_LEGACY_ICON_TRAY = "legacy_icon_tray"
        const val LEGACY_TRAY_WHITE = "white"
        const val LEGACY_TRAY_DOMINANT = "dominant"
        val FIELD_KEYS = listOf(
            KEY_HOME_COLS, KEY_HOME_ROWS, KEY_DOCK_SIZE, KEY_DRAWER_MODE, KEY_DRAWER_COLS, KEY_DRAWER_ROWS,
            KEY_DRAWER_SEARCH, KEY_DRAWER_SEARCH_POSITION, KEY_HOME_LABELS, KEY_DRAWER_LABELS, KEY_ICON_SCALE, KEY_ICON_DP,
            KEY_DOCK_ICON_DP, KEY_DRAWER_ICON_DP, KEY_LABEL_SP, KEY_LEGACY_ICON_TRAY,
        )
        /** A fraction of the short side outside this cannot be an app icon (One UI phones sit at 0.14 - 0.20). */
        const val MIN_ICON_FRACTION = 0.05f
        const val MAX_ICON_FRACTION = 0.5f
        const val MIN_ICON_DP = 24
        const val MAX_ICON_DP = 120
        const val MIN_ICON_SCALE = 0.5f
        const val MAX_ICON_SCALE = 1.5f
        /** A label smaller than 8 sp is unreadable, larger than 20 sp is not an app label; 0 means "ours". */
        const val MIN_LABEL_SP = 8f
        const val MAX_LABEL_SP = 20f
        const val KEY_SOURCE = "source"

        /** Our standard configuration: what a launcher gets when neither its APK nor a documented OEM default says otherwise. */
        val AOSP = HomeProfile(
            id = "aosp",
            homeCols = 5,
            homeRows = 5,
            dockSize = 5,
            drawerMode = DrawerMode.VERTICAL,
            drawerCols = 5,
            drawerRows = 0,
            drawerSearch = true,
            homeLabels = true,
            drawerLabels = true,
            source = ProfileSource.STANDARD,
        )

        /**
         * A profile from a rule's `profile` object. Missing fields fall back to [defaults] so a
         * console row only has to state what differs from AOSP. Returns null when there is no id or
         * a number is out of the range the grid can draw.
         */
        fun fromJson(o: JSONObject, defaults: HomeProfile = AOSP, source: ProfileSource): HomeProfile? {
            val id = o.optString(KEY_ID).trim()
            if (id.isEmpty()) return null
            val profile = HomeProfile(
                id = id,
                homeCols = o.optInt(KEY_HOME_COLS, defaults.homeCols),
                homeRows = o.optInt(KEY_HOME_ROWS, defaults.homeRows),
                dockSize = o.optInt(KEY_DOCK_SIZE, defaults.dockSize),
                drawerMode = drawerMode(o.optString(KEY_DRAWER_MODE)) ?: defaults.drawerMode,
                drawerCols = o.optInt(KEY_DRAWER_COLS, defaults.drawerCols),
                drawerRows = o.optInt(KEY_DRAWER_ROWS, defaults.drawerRows),
                drawerSearch = o.optBoolean(KEY_DRAWER_SEARCH, defaults.drawerSearch),
                homeLabels = o.optBoolean(KEY_HOME_LABELS, defaults.homeLabels),
                drawerLabels = o.optBoolean(KEY_DRAWER_LABELS, defaults.drawerLabels),
                source = source,
                drawerSearchPosition = drawerSearchPosition(o.optString(KEY_DRAWER_SEARCH_POSITION)) ?: defaults.drawerSearchPosition,
                iconScale = o.optDouble(KEY_ICON_SCALE, defaults.iconScale.toDouble()).toFloat(),
                labelSp = o.optDouble(KEY_LABEL_SP, defaults.labelSp.toDouble()).toFloat(),
                iconDp = o.optInt(KEY_ICON_DP, defaults.iconDp),
                dockIconDp = o.optInt(KEY_DOCK_ICON_DP, defaults.dockIconDp),
                drawerIconDp = o.optInt(KEY_DRAWER_ICON_DP, defaults.drawerIconDp),
                iconWidthFraction = o.optDouble(KEY_ICON_WIDTH_FRACTION, defaults.iconWidthFraction.toDouble()).toFloat(),
                dockIconWidthFraction = o.optDouble(KEY_DOCK_ICON_WIDTH_FRACTION, defaults.dockIconWidthFraction.toDouble()).toFloat(),
                drawerIconWidthFraction = o.optDouble(KEY_DRAWER_ICON_WIDTH_FRACTION, defaults.drawerIconWidthFraction.toDouble()).toFloat(),
                iconWidthFractionByDpi = o.optJSONObject(KEY_ICON_WIDTH_FRACTION_BY_DPI)?.let { table ->
                    table.keys().asSequence().mapNotNull { key ->
                        key.trim().toIntOrNull()?.let { dpi -> dpi to table.optDouble(key, Double.NaN).toFloat() }
                    }.toMap()
                } ?: defaults.iconWidthFractionByDpi,
                iconInset = o.optDouble(KEY_ICON_INSET, defaults.iconInset.toDouble()).toFloat(),
                legacyIconTray = o.optString(KEY_LEGACY_ICON_TRAY).trim().lowercase().ifEmpty { defaults.legacyIconTray },
            )
            return profile.takeIf { it.isSane() }
        }

        private fun drawerMode(raw: String): DrawerMode? =
            DrawerMode.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }

        private fun drawerSearchPosition(raw: String): DrawerSearchPosition? =
            DrawerSearchPosition.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }

        /** Bounds the grid can actually draw; a console typo must not produce a 0-column home. */
        const val MIN_CELLS = 2
        const val MAX_CELLS = 12
    }

    fun isSane(): Boolean =
        homeCols in MIN_CELLS..MAX_CELLS && homeRows in MIN_CELLS..MAX_CELLS &&
                dockSize in 0..MAX_CELLS && drawerCols in MIN_CELLS..MAX_CELLS &&
                drawerRows in 0..MAX_CELLS && !iconScale.isNaN() &&
                iconScale in MIN_ICON_SCALE..MAX_ICON_SCALE &&
                !labelSp.isNaN() && (labelSp == 0f || labelSp in MIN_LABEL_SP..MAX_LABEL_SP) &&
                (iconDp == 0 || iconDp in MIN_ICON_DP..MAX_ICON_DP) &&
                (dockIconDp == 0 || dockIconDp in MIN_ICON_DP..MAX_ICON_DP) &&
                (drawerIconDp == 0 || drawerIconDp in MIN_ICON_DP..MAX_ICON_DP) &&
                (listOf(iconWidthFraction, dockIconWidthFraction, drawerIconWidthFraction) + iconWidthFractionByDpi.values).all { !it.isNaN() && (it == 0f || it in MIN_ICON_FRACTION..MAX_ICON_FRACTION) } &&
                iconWidthFractionByDpi.keys.all { it in 100..1000 } &&
                !iconInset.isNaN() && (iconInset == 0f || iconInset in 0.5f..1f) &&
                legacyIconTray in setOf(LEGACY_TRAY_WHITE, LEGACY_TRAY_DOMINANT)
}
