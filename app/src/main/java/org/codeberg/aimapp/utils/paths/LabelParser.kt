// SPDX-License-Identifier: GPL-3.0-or-later

package org.codeberg.aimapp.utils.paths

import android.util.Log
import org.codeberg.aimapp.utils.mounts.FsType

private const val TAG = "LabelParser"
private const val MAX_LABEL_LENGTH = 64
private val EMPTY_LABELS = setOf("no name", "no_name", "noname", "")
private val LABEL_STEM_REGEX = Regex("^[A-Za-z0-9_-]+$")

fun isValidLabelStem(label: String): Boolean =
    label.isNotBlank() && label.length <= MAX_LABEL_LENGTH && LABEL_STEM_REGEX.matches(label)

fun labelToMountStem(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val normalized = raw.trim().lowercase().replace(Regex("[_\\s]+"), "")
    if (normalized in EMPTY_LABELS) return null
    val cleaned = raw.trim()
    if (!LABEL_STEM_REGEX.matches(cleaned)) return null
    val capped = if (cleaned.length > MAX_LABEL_LENGTH) cleaned.take(MAX_LABEL_LENGTH) else cleaned
    return capped
}

fun filenameToMountStem(filename: String): String {
    val replaced = filename.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "mounted_img" }
    val trimmed = replaced.trimStart('.')
    if (trimmed.isBlank() || trimmed == ".." || trimmed == ".") return "mounted_img"
    val capped = if (trimmed.length > MAX_LABEL_LENGTH) trimmed.take(MAX_LABEL_LENGTH) else trimmed
    return capped
}

fun probeLabel(
    probe: (skip: Int, count: Int) -> String, fsType: FsType, sizeBytes: Long = Long.MAX_VALUE
): String? {
    return try {
        val raw = when (fsType) {
            FsType.EXT4 -> probeExt4Label(probe)
            FsType.VFAT -> probeVfatLabel(probe)
            FsType.EXFAT -> probeExfatLabel(probe, sizeBytes)
            else -> null
        }
        labelToMountStem(raw)
    } catch (e: Exception) {
        Log.w(TAG, "Label probe failed for ${fsType.mountType}: ${e.message}")
        null
    }
}

private fun probeExt4Label(probe: (Int, Int) -> String): String? {
    val hex = probe(1144, 16) // 1024 + 120
    if (hex.length < 2) return null
    return decodeHexAsASCII(hex, nullTerminated = true)
}

private fun probeVfatLabel(probe: (Int, Int) -> String): String? {
    val fatSz16 = probe(22, 2)
    val labelOffset = if (fatSz16 == "0000") 71 else 43
    val hex = probe(labelOffset, 11)
    if (hex.length < 2) return null
    return decodeHexAsASCII(hex, nullTerminated = false)?.trimEnd()
}

private fun probeExfatLabel(probe: (Int, Int) -> String, sizeBytes: Long): String? {
    val oem = probe(3, 5)
    if (oem != "4558464154") return null // not "EXFAT"
    val bpsShift = probe(108, 1).toIntOrNull(16) ?: return null
    val spcShift = probe(109, 1).toIntOrNull(16) ?: return null
    if (bpsShift !in 9..12 || spcShift !in 0..25) {
        Log.w(TAG, "exFAT: bad shift values bps=$bpsShift spc=$spcShift")
        return null
    }
    val bytesPerSector = 1L shl bpsShift
    val sectorsPerCluster = 1L shl spcShift
    val clusterHeapOffsetHex = probe(88, 4)
    val rootDirClusterHex = probe(96, 4)
    if (clusterHeapOffsetHex.length < 8 || rootDirClusterHex.length < 8) return null
    val clusterHeapOffset = parseL3U32Hex(clusterHeapOffsetHex)
    val rootDirCluster = parseL3U32Hex(rootDirClusterHex)
    if (rootDirCluster < 2) {
        Log.w(TAG, "exFAT: invalid root dir cluster $rootDirCluster")
        return null
    }
    val rootDirByteOffset =
        (clusterHeapOffset + (rootDirCluster - 2) * sectorsPerCluster) * bytesPerSector
    if (rootDirByteOffset !in 0..<sizeBytes) {
        Log.w(TAG, "exFAT: root dir offset $rootDirByteOffset out of bounds (size=$sizeBytes)")
        return null
    }
    val scanBytes = (sizeBytes - rootDirByteOffset).coerceIn(0L, 4096L).toInt()
    if (scanBytes < 32) return null
    if (rootDirByteOffset > Int.MAX_VALUE) {
        Log.w(TAG, "exFAT: root dir offset too large for probe: $rootDirByteOffset")
        return null
    }
    val rootDirHex = probe(rootDirByteOffset.toInt(), scanBytes)
    if (rootDirHex.length < 64) return null  // need at least one 32-byte entry
    val numEntries = rootDirHex.length / 64  // 32 bytes = 64 hex chars per entry
    for (i in 0 until numEntries) {
        val entryBase = i * 64
        val entryType = rootDirHex.substring(entryBase, entryBase + 2).toIntOrNull(16) ?: continue
        if (entryType == 0x00) break  // end of directory
        if (entryType != 0x83) continue  // not a volume label entry
        val charCount =
            rootDirHex.substring(entryBase + 2, entryBase + 4).toIntOrNull(16) ?: return null
        if (charCount == 0 || charCount > 11) return null
        val labelHexStart = entryBase + 4  // byte 2 of entry
        val labelHexEnd = labelHexStart + charCount * 4  // 2 bytes per char = 4 hex chars
        if (labelHexEnd > rootDirHex.length) return null
        val labelHex = rootDirHex.substring(labelHexStart, labelHexEnd)
        return decodeHexAsUTF16LE(labelHex)
    }
    return null
}

private fun decodeHexAsASCII(hex: String, nullTerminated: Boolean): String? {
    val sb = StringBuilder(hex.length / 2)
    for (i in hex.indices step 2) {
        if (i + 1 >= hex.length) break
        val byte = hex.substring(i, i + 2).toIntOrNull(16) ?: break
        if (nullTerminated && byte == 0) break
        sb.append(byte.toChar())
    }
    return sb.toString().ifEmpty { null }
}

private fun decodeHexAsUTF16LE(hex: String): String? {
    val chars = CharArray(hex.length / 4)
    var idx = 0
    for (i in hex.indices step 4) {
        if (i + 3 >= hex.length) break
        val lo = hex.substring(i, i + 2).toIntOrNull(16) ?: break
        val hi = hex.substring(i + 2, i + 4).toIntOrNull(16) ?: break
        val codeUnit = lo or (hi shl 8)
        if (codeUnit == 0) break
        chars[idx++] = codeUnit.toChar()
    }
    return if (idx > 0) String(chars, 0, idx) else null
}

private fun parseL3U32Hex(hex: String): Long {
    require(hex.length >= 8) { "Need 8 hex chars, got ${hex.length}" }
    val b0 = hex.take(2).toLong(16)
    val b1 = hex.substring(2, 4).toLong(16)
    val b2 = hex.substring(4, 6).toLong(16)
    val b3 = hex.substring(6, 8).toLong(16)
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}
