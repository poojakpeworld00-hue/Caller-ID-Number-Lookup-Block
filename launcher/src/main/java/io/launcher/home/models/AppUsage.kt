package io.launcher.home.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per app the user has opened through this launcher - home, dock, drawer or search all
 * launch through the same call, so this is the launcher's own record of "most" and "recently"
 * opened. Nothing system-wide is read (that would need Usage access) and nothing leaves the device.
 */
@Entity(tableName = "app_usage")
data class AppUsage(
    @PrimaryKey @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "activity_name") val activityName: String,
    @ColumnInfo(name = "launch_count") val launchCount: Int,
    @ColumnInfo(name = "last_launched") val lastLaunched: Long,
)
