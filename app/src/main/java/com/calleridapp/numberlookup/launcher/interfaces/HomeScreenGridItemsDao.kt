package com.calleridapp.numberlookup.launcher.interfaces

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.calleridapp.numberlookup.launcher.databases.AppsDatabase.Companion.TABLE_GRID_ITEMS
import com.calleridapp.numberlookup.launcher.helpers.Converters
import com.calleridapp.numberlookup.launcher.models.HomeScreenGridItem

/** Everything placed on the home screen: icons, folders, shortcuts and widgets. */
class HomeScreenGridItemsDao(private val helper: SQLiteOpenHelper) {

    private val converters = Converters()

    fun getAllItems(): List<HomeScreenGridItem> =
        query("SELECT * FROM $TABLE_GRID_ITEMS", null)

    fun getFolderItems(folderId: Long): List<HomeScreenGridItem> =
        query(
            "SELECT * FROM $TABLE_GRID_ITEMS WHERE parent_id = ?",
            arrayOf(folderId.toString())
        )

    fun insert(item: HomeScreenGridItem): Long =
        helper.writableDatabase.insertWithOnConflict(
            TABLE_GRID_ITEMS, null, item.toContentValues(), SQLiteDatabase.CONFLICT_REPLACE
        )

    fun insertAll(items: List<HomeScreenGridItem>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            items.forEach {
                db.insertWithOnConflict(
                    TABLE_GRID_ITEMS, null, it.toContentValues(), SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateAppTitle(title: String, packageName: String) {
        helper.writableDatabase.execSQL(
            "UPDATE $TABLE_GRID_ITEMS SET title = ? WHERE package_name = ?",
            arrayOf(title, packageName)
        )
    }

    fun updateItemTitle(title: String, id: Long): Int =
        helper.writableDatabase.update(
            TABLE_GRID_ITEMS,
            ContentValues().apply { put("title", title) },
            "id = ?",
            arrayOf(id.toString())
        )

    fun updateItemPosition(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        page: Int,
        docked: Boolean,
        parentId: Long?,
        id: Long,
    ) {
        val values = ContentValues().apply {
            put("`left`", left)
            put("`top`", top)
            put("`right`", right)
            put("`bottom`", bottom)
            put("page", page)
            put("docked", if (docked) 1 else 0)
            if (parentId == null) putNull("parent_id") else put("parent_id", parentId)
        }

        helper.writableDatabase.update(TABLE_GRID_ITEMS, values, "id = ?", arrayOf(id.toString()))
    }

    fun updateWidgetId(widgetId: Int, id: Long): Int =
        helper.writableDatabase.update(
            TABLE_GRID_ITEMS,
            ContentValues().apply { put("widget_id", widgetId) },
            "id = ?",
            arrayOf(id.toString())
        )

    fun deleteItemById(id: Long) {
        helper.writableDatabase.delete(TABLE_GRID_ITEMS, "id = ?", arrayOf(id.toString()))
    }

    fun deleteItemsWithParentId(id: Long) {
        helper.writableDatabase.delete(TABLE_GRID_ITEMS, "parent_id = ?", arrayOf(id.toString()))
    }

    /** Removes an item and, if it was a folder, everything that was inside it. */
    fun deleteById(id: Long) = inTransaction {
        deleteItemById(id)
        deleteItemsWithParentId(id)
    }

    fun deleteItemByPackageName(packageName: String) {
        helper.writableDatabase.delete(
            TABLE_GRID_ITEMS, "package_name = ?", arrayOf(packageName)
        )
    }

    fun deleteItemsByParentPackageName(packageName: String) {
        helper.writableDatabase.execSQL(
            "DELETE FROM $TABLE_GRID_ITEMS WHERE parent_id IN " +
                "(SELECT id FROM $TABLE_GRID_ITEMS WHERE package_name = ?)",
            arrayOf(packageName)
        )
    }

    /**
     * Slides the items after [shiftFrom] inside a folder over by [shiftBy], leaving the item
     * being moved ([excludingId]) where it is.
     */
    fun shiftFolderItems(folderId: Long, shiftFrom: Int, shiftBy: Int, excludingId: Long? = null) {
        helper.writableDatabase.execSQL(
            "UPDATE $TABLE_GRID_ITEMS SET `left` = `left` + ? " +
                "WHERE parent_id = ? AND `left` > ? AND (? IS NULL OR id != ?)",
            arrayOf<Any?>(shiftBy, folderId, shiftFrom, excludingId, excludingId)
        )
    }

    fun shiftPage(shiftFrom: Int, shiftBy: Int) {
        helper.writableDatabase.execSQL(
            "UPDATE $TABLE_GRID_ITEMS SET page = page + ? WHERE page > ?",
            arrayOf<Any?>(shiftBy, shiftFrom)
        )
    }

    /** Removes an app's icon along with any copies of it sitting inside folders. */
    fun deleteByPackageName(packageName: String) = inTransaction {
        deleteItemByPackageName(packageName)
        deleteItemsByParentPackageName(packageName)
    }

    private inline fun inTransaction(block: () -> Unit) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun query(sql: String, args: Array<String>?): List<HomeScreenGridItem> {
        val items = ArrayList<HomeScreenGridItem>()
        helper.readableDatabase.rawQuery(sql, args).use { cursor ->
            val id = cursor.getColumnIndexOrThrow("id")
            val left = cursor.getColumnIndexOrThrow("left")
            val top = cursor.getColumnIndexOrThrow("top")
            val right = cursor.getColumnIndexOrThrow("right")
            val bottom = cursor.getColumnIndexOrThrow("bottom")
            val page = cursor.getColumnIndexOrThrow("page")
            val packageName = cursor.getColumnIndexOrThrow("package_name")
            val activityName = cursor.getColumnIndexOrThrow("activity_name")
            val title = cursor.getColumnIndexOrThrow("title")
            val type = cursor.getColumnIndexOrThrow("type")
            val className = cursor.getColumnIndexOrThrow("class_name")
            val widgetId = cursor.getColumnIndexOrThrow("widget_id")
            val shortcutId = cursor.getColumnIndexOrThrow("shortcut_id")
            val icon = cursor.getColumnIndexOrThrow("icon")
            val docked = cursor.getColumnIndexOrThrow("docked")
            val parentId = cursor.getColumnIndexOrThrow("parent_id")

            while (cursor.moveToNext()) {
                items += HomeScreenGridItem(
                    id = cursor.getLong(id),
                    left = cursor.getInt(left),
                    top = cursor.getInt(top),
                    right = cursor.getInt(right),
                    bottom = cursor.getInt(bottom),
                    page = cursor.getInt(page),
                    packageName = cursor.getString(packageName),
                    activityName = cursor.getString(activityName),
                    title = cursor.getString(title),
                    type = cursor.getInt(type),
                    className = cursor.getString(className),
                    widgetId = cursor.getInt(widgetId),
                    shortcutId = cursor.getString(shortcutId),
                    icon = converters.toBitmap(cursor.getBlobOrNull(icon)),
                    docked = cursor.getInt(docked) != 0,
                    parentId = if (cursor.isNull(parentId)) null else cursor.getLong(parentId),
                )
            }
        }

        return items
    }

    private fun Cursor.getBlobOrNull(index: Int): ByteArray? =
        if (isNull(index)) null else getBlob(index)

    private fun HomeScreenGridItem.toContentValues() = ContentValues().apply {
        id?.let { put("id", it) }
        put("`left`", left)
        put("`top`", top)
        put("`right`", right)
        put("`bottom`", bottom)
        put("page", page)
        put("package_name", packageName)
        put("activity_name", activityName)
        put("title", title)
        put("type", type)
        put("class_name", className)
        put("widget_id", widgetId)
        put("shortcut_id", shortcutId)
        put("icon", converters.fromBitmap(icon))
        put("docked", if (docked) 1 else 0)
        if (parentId == null) putNull("parent_id") else put("parent_id", parentId)
    }
}
