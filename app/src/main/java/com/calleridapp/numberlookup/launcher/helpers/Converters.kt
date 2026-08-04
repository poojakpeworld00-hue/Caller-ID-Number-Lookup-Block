package com.calleridapp.numberlookup.launcher.helpers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/** Bitmap <-> BLOB for the pinned-shortcut icons stored in the grid table. */
class Converters {
    fun toBitmap(bytes: ByteArray?): Bitmap? {
        return if (bytes == null) {
            null
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    fun fromBitmap(bmp: Bitmap?): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bmp?.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        return outputStream.toByteArray()
    }
}
