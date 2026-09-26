package io.launcher.home.interfaces

import androidx.room.*
import io.launcher.home.models.HiddenIcon

@Dao
interface HiddenIconsDao {
    @Query("SELECT * FROM hidden_icons")
    fun getHiddenIcons(): List<HiddenIcon>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(hiddenIconUi: HiddenIcon): Long

    @Delete
    fun removeHiddenIcons(icons: List<HiddenIcon>)
}
