package io.launcher.home.profile

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * The resource *names* an installed launcher declares, read straight out of its APK's
 * `resources.arsc` (public file, `java.util.zip`, no permission, no library). Android's
 * `Resources` API can read any resource by name but cannot list them; this is the listing, so the
 * generic readers can look for "whatever this launcher calls its column count" instead of
 * knowing each OEM's names in advance. Values are then read through `Resources`, which resolves
 * them for this device's configuration and any RRO overlay in force.
 *
 * Only the four types the readers use are kept: `integer`, `dimen`, `bool`, `xml`.
 */
class ApkResourceIndex private constructor(private val names: Map<String, Set<String>>) {

    fun names(type: String): Set<String> = names[type].orEmpty()

    val isEmpty: Boolean get() = names.values.all { it.isEmpty() }

    companion object {
        private val WANTED_TYPES = setOf("integer", "dimen", "bool", "xml")
        private const val MAX_ARSC_BYTES = 24 * 1024 * 1024

        private const val RES_STRING_POOL_TYPE = 0x0001
        private const val RES_TABLE_TYPE = 0x0002
        private const val RES_TABLE_PACKAGE_TYPE = 0x0200
        private const val RES_TABLE_TYPE_TYPE = 0x0201
        private const val FLAG_SPARSE = 0x01
        private const val FLAG_OFFSET16 = 0x02
        private const val ENTRY_FLAG_COMPACT = 0x0008
        private const val UTF8_FLAG = 0x100

        /** Null when the APK cannot be opened or its table is not what this parser understands. */
        fun read(apkPath: String): ApkResourceIndex? = runCatching {
            val file = File(apkPath)
            if (!file.canRead()) return null
            val bytes = ZipFile(file).use { zip ->
                val entry = zip.getEntry("resources.arsc") ?: return null
                if (entry.size > MAX_ARSC_BYTES) return null
                zip.getInputStream(entry).use { it.readBytes() }
            }
            parse(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
        }.getOrNull()

        internal fun parse(buf: ByteBuffer): ApkResourceIndex? {
            if (buf.remaining() < 12 || buf.getShort(0).toInt() and 0xFFFF != RES_TABLE_TYPE) return null
            val tableHeaderSize = buf.getShort(2).toInt() and 0xFFFF
            val tableSize = buf.getInt(4)
            val result = HashMap<String, MutableSet<String>>()
            var pos = tableHeaderSize
            while (pos + 8 <= minOf(tableSize, buf.limit())) {
                val type = buf.getShort(pos).toInt() and 0xFFFF
                val size = buf.getInt(pos + 4)
                if (size <= 0) break
                if (type == RES_TABLE_PACKAGE_TYPE) parsePackage(buf, pos, size, result)
                pos += size
            }
            return ApkResourceIndex(result)
        }

        private fun parsePackage(buf: ByteBuffer, start: Int, size: Int, into: MutableMap<String, MutableSet<String>>) {
            val headerSize = buf.getShort(start + 2).toInt() and 0xFFFF
            val typeStringsOffset = buf.getInt(start + 8 + 4 + 256)
            val keyStringsOffset = buf.getInt(start + 8 + 4 + 256 + 8)
            val typeNames = readStringPool(buf, start + typeStringsOffset)
            val keyNames = readStringPool(buf, start + keyStringsOffset)
            val end = minOf(start + size, buf.limit())
            var pos = start + headerSize
            while (pos + 8 <= end) {
                val chunkType = buf.getShort(pos).toInt() and 0xFFFF
                val chunkHeaderSize = buf.getShort(pos + 2).toInt() and 0xFFFF
                val chunkSize = buf.getInt(pos + 4)
                if (chunkSize <= 0) break
                if (chunkType == RES_TABLE_TYPE_TYPE) {
                    val typeId = buf.get(pos + 8).toInt() and 0xFF
                    val typeName = typeNames.getOrNull(typeId - 1)
                    if (typeName != null && typeName in WANTED_TYPES) {
                        readTypeChunk(buf, pos, chunkHeaderSize, chunkSize, keyNames, into.getOrPut(typeName) { HashSet() })
                    }
                }
                pos += chunkSize
            }
        }

        private fun readTypeChunk(buf: ByteBuffer, start: Int, headerSize: Int, size: Int, keys: List<String>, into: MutableSet<String>) {
            val flags = buf.get(start + 9).toInt() and 0xFF
            val entryCount = buf.getInt(start + 12)
            val entriesStart = buf.getInt(start + 16)
            if (entryCount <= 0 || entryCount > 65536) return
            val offsetsAt = start + headerSize
            val end = minOf(start + size, buf.limit())
            for (i in 0 until entryCount) {
                val entryOffset: Int = when {
                    flags and FLAG_SPARSE != 0 -> {
                        val at = offsetsAt + i * 4
                        if (at + 4 > end) return
                        (buf.getShort(at + 2).toInt() and 0xFFFF) * 4
                    }
                    flags and FLAG_OFFSET16 != 0 -> {
                        val at = offsetsAt + i * 2
                        if (at + 2 > end) return
                        val v = buf.getShort(at).toInt() and 0xFFFF
                        if (v == 0xFFFF) continue else v * 4
                    }
                    else -> {
                        val at = offsetsAt + i * 4
                        if (at + 4 > end) return
                        val v = buf.getInt(at)
                        if (v == -1) continue else v
                    }
                }
                val entryAt = start + entriesStart + entryOffset
                if (entryAt + 8 > end) continue
                val first = buf.getShort(entryAt).toInt() and 0xFFFF
                val entryFlags = buf.getShort(entryAt + 2).toInt() and 0xFFFF
                val keyIndex = if (entryFlags and ENTRY_FLAG_COMPACT != 0) first else buf.getInt(entryAt + 4)
                keys.getOrNull(keyIndex)?.let(into::add)
            }
        }

        private fun readStringPool(buf: ByteBuffer, start: Int): List<String> {
            if (start + 28 > buf.limit() || buf.getShort(start).toInt() and 0xFFFF != RES_STRING_POOL_TYPE) return emptyList()
            val headerSize = buf.getShort(start + 2).toInt() and 0xFFFF
            val count = buf.getInt(start + 8)
            val flags = buf.getInt(start + 16)
            val stringsStart = buf.getInt(start + 20)
            if (count <= 0 || count > 200_000) return emptyList()
            val utf8 = flags and UTF8_FLAG != 0
            val out = ArrayList<String>(count)
            for (i in 0 until count) {
                val offAt = start + headerSize + i * 4
                if (offAt + 4 > buf.limit()) break
                var at = start + stringsStart + buf.getInt(offAt)
                if (at < 0 || at >= buf.limit()) { out.add(""); continue }
                if (utf8) {
                    // char count (u8, or u16 with the high bit), then byte count in the same form
                    var c = buf.get(at).toInt() and 0xFF; at += if (c and 0x80 != 0) 2 else 1
                    var b = buf.get(at).toInt() and 0xFF
                    if (b and 0x80 != 0) { b = ((b and 0x7F) shl 8) or (buf.get(at + 1).toInt() and 0xFF); at += 2 } else at += 1
                    if (at + b > buf.limit()) { out.add(""); continue }
                    val bytes = ByteArray(b); for (k in 0 until b) bytes[k] = buf.get(at + k)
                    out.add(String(bytes, Charsets.UTF_8))
                } else {
                    var len = buf.getShort(at).toInt() and 0xFFFF; at += 2
                    if (len and 0x8000 != 0) { len = ((len and 0x7FFF) shl 16) or (buf.getShort(at).toInt() and 0xFFFF); at += 2 }
                    if (at + len * 2 > buf.limit()) { out.add(""); continue }
                    val chars = CharArray(len); for (k in 0 until len) chars[k] = buf.getChar(at + k * 2)
                    out.add(String(chars))
                }
            }
            return out
        }
    }
}
