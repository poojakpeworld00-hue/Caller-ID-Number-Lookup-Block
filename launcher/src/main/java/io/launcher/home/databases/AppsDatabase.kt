package io.launcher.home.databases

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.launcher.home.helpers.Converters
import io.launcher.home.interfaces.AppLaunchersDao
import io.launcher.home.interfaces.AppUsageDao
import io.launcher.home.interfaces.HiddenIconsDao
import io.launcher.home.interfaces.HomeScreenGridItemsDao
import io.launcher.home.models.AppLauncher
import io.launcher.home.models.AppUsage
import io.launcher.home.models.HiddenIcon
import io.launcher.home.models.HomeScreenGridItem

@Database(
    entities = [AppLauncher::class, HomeScreenGridItem::class, HiddenIcon::class, AppUsage::class],
    version = 6
)
@TypeConverters(Converters::class)
abstract class AppsDatabase : RoomDatabase() {

    abstract fun AppLaunchersDao(): AppLaunchersDao

    abstract fun HomeScreenGridItemsDao(): HomeScreenGridItemsDao

    abstract fun HiddenIconsDao(): HiddenIconsDao

    abstract fun AppUsageDao(): AppUsageDao

    companion object {
        private var db: AppsDatabase? = null

        /** The launcher's own launch log for the search's suggested apps. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `app_usage` (`package_name` TEXT NOT NULL, `activity_name` TEXT NOT NULL, " +
                        "`launch_count` INTEGER NOT NULL, `last_launched` INTEGER NOT NULL, PRIMARY KEY(`package_name`))"
                )
            }
        }

        fun getInstance(context: Context): AppsDatabase {
            if (db == null) {
                synchronized(AppsDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(
                            context.applicationContext,
                            AppsDatabase::class.java,
                            "apps.db"
                        ).addMigrations(MIGRATION_5_6).build()
                    }
                }
            }
            return db!!
        }
    }
}
