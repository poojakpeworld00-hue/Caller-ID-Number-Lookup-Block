package io.launcher.home.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.launcher.home.models.AppUsage

@Dao
interface AppUsageDao {
    @Query("SELECT * FROM app_usage")
    fun getAll(): List<AppUsage>

    @Query("SELECT * FROM app_usage WHERE package_name = :packageName")
    fun get(packageName: String): AppUsage?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(usage: AppUsage)

    @Query("DELETE FROM app_usage WHERE package_name = :packageName")
    fun remove(packageName: String)

    /** Counts one more launch of the app, now. */
    @Transaction
    fun recordLaunch(packageName: String, activityName: String, now: Long = System.currentTimeMillis()) {
        val previous = get(packageName)
        insert(AppUsage(packageName, activityName, (previous?.launchCount ?: 0) + 1, now))
    }
}
