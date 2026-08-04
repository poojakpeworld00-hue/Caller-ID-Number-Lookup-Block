package com.calleridapp.numberlookup.launcher.interfaces

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.calleridapp.numberlookup.launcher.databases.AppsDatabase.Companion.TABLE_HIDDEN_ICONS
import com.calleridapp.numberlookup.launcher.models.HiddenIcon

/** Icons the user has hidden from the app drawer. */
class HiddenIconsDao(private val helper: SQLiteOpenHelper) {

    fun getHiddenIcons(): List<HiddenIcon> {
        val icons = ArrayList<HiddenIcon>()
        helper.readableDatabase.rawQuery("SELECT * FROM $TABLE_HIDDEN_ICONS", null).use { cursor ->
            val id = cursor.getColumnIndexOrThrow("id")
            val packageName = cursor.getColumnIndexOrThrow("package_name")
            val activityName = cursor.getColumnIndexOrThrow("activity_name")
            val title = cursor.getColumnIndexOrThrow("title")

            while (cursor.moveToNext()) {
                icons += HiddenIcon(
                    id = cursor.getLong(id),
                    packageName = cursor.getString(packageName),
                    activityName = cursor.getString(activityName),
                    title = cursor.getString(title),
                )
            }
        }

        return icons
    }

    fun insert(hiddenIcon: HiddenIcon): Long {
        val values = ContentValues().apply {
            hiddenIcon.id?.let { put("id", it) }
            put("package_name", hiddenIcon.packageName)
            put("activity_name", hiddenIcon.activityName)
            put("title", hiddenIcon.title)
        }

        return helper.writableDatabase.insertWithOnConflict(
            TABLE_HIDDEN_ICONS, null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun removeHiddenIcons(icons: List<HiddenIcon>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            icons.forEach { icon ->
                val id = icon.id ?: return@forEach
                db.delete(TABLE_HIDDEN_ICONS, "id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
