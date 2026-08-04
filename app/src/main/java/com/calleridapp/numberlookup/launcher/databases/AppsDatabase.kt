package com.calleridapp.numberlookup.launcher.databases

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.calleridapp.numberlookup.launcher.interfaces.AppLaunchersDao
import com.calleridapp.numberlookup.launcher.interfaces.HiddenIconsDao
import com.calleridapp.numberlookup.launcher.interfaces.HomeScreenGridItemsDao

/**
 * The launcher's own store: the app-drawer cache, the home-screen grid and the icons the user has
 * hidden from the drawer.
 *
 * Plain SQLite rather than Room — AGP 9's built-in Kotlin support refuses to run KSP, and the
 * standalone Kotlin plugin that KSP would need does not support AGP 9. The DAOs keep the exact
 * method signatures Room used to generate, so nothing else in the launcher had to change.
 */
class AppsDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    private val appLaunchersDao by lazy { AppLaunchersDao(this) }
    private val homeScreenGridItemsDao by lazy { HomeScreenGridItemsDao(this) }
    private val hiddenIconsDao by lazy { HiddenIconsDao(this) }

    @Suppress("FunctionName")
    fun AppLaunchersDao(): AppLaunchersDao = appLaunchersDao

    @Suppress("FunctionName")
    fun HomeScreenGridItemsDao(): HomeScreenGridItemsDao = homeScreenGridItemsDao

    @Suppress("FunctionName")
    fun HiddenIconsDao(): HiddenIconsDao = hiddenIconsDao

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_APPS)
        db.execSQL(CREATE_APPS_INDEX)
        db.execSQL(CREATE_GRID_ITEMS)
        db.execSQL(CREATE_HIDDEN_ICONS)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // The drawer cache and the grid are both rebuilt from the installed apps on the next
        // launch, so there is nothing here worth migrating.
        db.execSQL("DROP TABLE IF EXISTS $TABLE_APPS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_GRID_ITEMS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_HIDDEN_ICONS")
        onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        onUpgrade(db, oldVersion, newVersion)

    companion object {
        private const val DB_NAME = "apps.db"
        private const val DB_VERSION = 5

        const val TABLE_APPS = "apps"
        const val TABLE_GRID_ITEMS = "home_screen_grid_items"
        const val TABLE_HIDDEN_ICONS = "hidden_icons"

        private const val CREATE_APPS = """
            CREATE TABLE IF NOT EXISTS $TABLE_APPS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                package_name TEXT NOT NULL,
                activity_name TEXT NOT NULL,
                `order` INTEGER NOT NULL,
                thumbnail_color INTEGER NOT NULL
            )
        """

        private const val CREATE_APPS_INDEX =
            "CREATE UNIQUE INDEX IF NOT EXISTS index_apps_package_name ON $TABLE_APPS (package_name)"

        private const val CREATE_GRID_ITEMS = """
            CREATE TABLE IF NOT EXISTS $TABLE_GRID_ITEMS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                `left` INTEGER NOT NULL,
                `top` INTEGER NOT NULL,
                `right` INTEGER NOT NULL,
                `bottom` INTEGER NOT NULL,
                page INTEGER NOT NULL,
                package_name TEXT NOT NULL,
                activity_name TEXT NOT NULL,
                title TEXT NOT NULL,
                type INTEGER NOT NULL,
                class_name TEXT NOT NULL,
                widget_id INTEGER NOT NULL,
                shortcut_id TEXT NOT NULL,
                icon BLOB,
                docked INTEGER NOT NULL,
                parent_id INTEGER
            )
        """

        private const val CREATE_HIDDEN_ICONS = """
            CREATE TABLE IF NOT EXISTS $TABLE_HIDDEN_ICONS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                package_name TEXT NOT NULL,
                activity_name TEXT NOT NULL,
                title TEXT NOT NULL
            )
        """

        @Volatile
        private var instance: AppsDatabase? = null

        fun getInstance(context: Context): AppsDatabase {
            return instance ?: synchronized(this) {
                instance ?: AppsDatabase(context).also { instance = it }
            }
        }
    }
}
