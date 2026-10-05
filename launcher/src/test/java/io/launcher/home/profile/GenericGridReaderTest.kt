package io.launcher.home.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericGridReaderTest {

    // Integer names as MIUI HyperOS 7.50 and ColorOS 14 launchers declare them (from their APKs).
    private val miui = listOf(
        "THUMBNAIL_VIEW_COLUMN_NUM", "config_cell_count_x", "config_cell_count_x_drawer_mode_large_fold",
        "config_cell_count_x_drawer_mode_small_fold", "config_cell_count_x_max", "config_cell_count_x_min",
        "config_cell_count_x_mishow", "config_cell_count_y", "config_cell_count_y_max", "config_cell_count_y_min",
        "config_cell_count_y_mishow", "config_widget_cell_count", "db_screen_create_index", "all_apps_animation_duration",
    )
    private val coloros = listOf(
        "big_simple_hotseat_height_dp", "bubbles_overflow_columns", "config_default_grid_x", "default_columns",
        "device_profiles_display_option_oplusAllAppsCellHeightDp", "device_profiles_display_option_oplusnumAllAppsColumns",
        "device_profiles_display_option_oplusnumColumns", "device_profiles_display_option_oplusnumRows",
        "grid_guide_column_bottom_sheet_dialog", "grid_guide_column_preference", "inner_responsive_ui_column_4",
        "layout_grid_column_12", "layout_grid_column_4", "recentThumbnailCacheSize_grid", "taskbar_grid_guide_column_preference",
    )

    @Test
    fun miuiNamesRankTheFactoryGridFirst() {
        assertEquals("config_cell_count_x", GenericGridReader.rank(GenericGridReader.Field.HOME_COLS, miui).first())
        assertEquals("config_cell_count_y", GenericGridReader.rank(GenericGridReader.Field.HOME_ROWS, miui).first())
        // variants never make the list at all
        assertTrue(GenericGridReader.rank(GenericGridReader.Field.HOME_COLS, miui).none { it.contains("max") || it.contains("fold") || it.contains("mishow") })
    }

    @Test
    fun colorosNamesRankTheFactoryGridFirst() {
        val cols = GenericGridReader.rank(GenericGridReader.Field.HOME_COLS, coloros)
        assertTrue(cols.first() == "default_columns" || cols.first() == "config_default_grid_x")
        assertTrue("bubbles must not be first: $cols", cols.first() != "bubbles_overflow_columns")
        assertEquals("device_profiles_display_option_oplusnumRows", GenericGridReader.rank(GenericGridReader.Field.HOME_ROWS, coloros).first())
        assertEquals("device_profiles_display_option_oplusnumAllAppsColumns", GenericGridReader.rank(GenericGridReader.Field.DRAWER_COLS, coloros).first())
        assertTrue(GenericGridReader.rank(GenericGridReader.Field.HOME_COLS, coloros).none { it.contains("guide") || it.contains("taskbar") || it.contains("responsive") })
    }

    // One UI Home 17.5 (Galaxy A35, One UI 8.5): no home grid in resources at all - only look-alikes.
    private val samsung = listOf(
        "column_count_for_directory_card", "column_count_for_list_card", "desk_column_count", "desk_row_count",
        "suggested_apps_default_grid_x", "suggested_apps_default_grid_y", "suggested_apps_grid_x_fold_main",
        "hotseat_box_max_x", "row_gap_phone", "result_thumbnail5_grid_span_count", "page_spacing_grid_phone",
    )

    @Test
    fun samsungLookAlikesAreNotAGrid() {
        assertTrue(GenericGridReader.rank(GenericGridReader.Field.HOME_COLS, samsung).isEmpty())
        assertTrue(GenericGridReader.rank(GenericGridReader.Field.HOME_ROWS, samsung).isEmpty())
    }

    @Test
    fun rowsPairWithColumnsByStem() {
        assertEquals("config_cell_count_y", GenericGridReader.rowSibling("config_cell_count_x"))
        assertEquals("device_profiles_display_option_oplusnumRows", GenericGridReader.rowSibling("device_profiles_display_option_oplusnumColumns"))
        assertEquals("default_rows", GenericGridReader.rowSibling("default_columns"))
        assertEquals("config_default_grid_y", GenericGridReader.rowSibling("config_default_grid_x"))
        assertEquals("desk_row_count", GenericGridReader.rowSibling("desk_column_count"))
        assertEquals(null, GenericGridReader.rowSibling("something_else"))
    }

    // One UI Home 17.5 dimen names around icons and labels. `app_icon_size` (63 dp) is the drawn
    // workspace icon; the `_for_*_device` variants only pick the icon *bitmap* density
    // (IconBaseInfo.getResId), and `label_text_size` (12.5 sp) belongs to a list - the workspace
    // label is 11 dp from PhoneItemStyleFactory, not a resource.
    private val samsungDimens = listOf(
        "app_icon_size", "app_icon_size_for_foldable_device", "app_icon_size_for_mass_device", "app_icon_size_for_tablet_device",
        "app_update_badge_icon_size", "badge_icon_size", "card_item_apps_icon_size", "fallback_icon_size_over_medium",
        "label_text_size", "min_icon_text_size", "icon_text_size_ratio", "task_label_text_size", "launch_app_item_label_text_size",
        "slim_list_app_label_textsize", "vertical_list_app_label_textsize", "task_switcher_app_label_text_size", "title_text_size",
        "add_item_activity_label_text_size", "qs_tile_label_text_size", "history_title_text_size",
    )

    @Test
    fun samsungIconSizeIsThePlainOneNeverADeviceVariant() {
        assertEquals(listOf("app_icon_size"), GenericGridReader.rank(GenericGridReader.Field.ICON_DP, samsungDimens))
    }

    @Test
    fun samsungLabelDimensAreNotTheWorkspaceLabel() {
        assertTrue(GenericGridReader.rank(GenericGridReader.Field.LABEL_SP, samsungDimens).isEmpty())
    }

    @Test
    fun labelNamesNeedAnIconOrAppStem() {
        val names = listOf("icon_text_size", "app_label_text_size", "workspace_icon_text_size", "icon_title_text_size", "home_label_size",
            "label_text_size", "title_text_size", "min_icon_text_size", "folder_icon_text_size", "all_apps_icon_text_size", "icon_text_size_land")
        assertEquals(
            setOf("icon_text_size", "home_label_size", "app_label_text_size", "icon_title_text_size", "workspace_icon_text_size"),
            GenericGridReader.rank(GenericGridReader.Field.LABEL_SP, names).toSet(),
        )
    }

    @Test
    fun nothingMatchesNothing() {
        assertTrue(GenericGridReader.rank(GenericGridReader.Field.HOME_COLS, listOf("anim_duration", "color_primary")).isEmpty())
    }
}
