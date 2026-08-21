// SPDX-License-Identifier: GPL-3.0-or-later

package org.codeberg.aimapp.utils.mounts

import android.content.Context
import android.util.Log
import org.codeberg.aimapp.R
import org.codeberg.aimapp.utils.disk.EXFAT_OEM_HEX
import org.codeberg.aimapp.utils.disk.FAT_SIG_HEX
import org.codeberg.aimapp.utils.disk.VALID_FAT_BPS
import org.codeberg.aimapp.utils.disk.VALID_FAT_NFATS
import org.codeberg.aimapp.utils.disk.detectFatVariant
import org.codeberg.aimapp.utils.disk.hexProbe
import org.codeberg.aimapp.utils.disk.probeFsMagic
import org.codeberg.aimapp.utils.parseLeHexAt
import org.codeberg.aimapp.utils.paths.labelToMountStem
import org.codeberg.aimapp.utils.paths.probeLabel
import java.io.File

private const val TAG = "PartitionTable"
private val PARTITION_TYPE_NAMES = mapOf(
    0x01 to "FAT12",
    0x04 to "FAT16 (<32 MB)",
    0x05 to "Extended",
    0x06 to "FAT16",
    0x07 to "NTFS/exFAT/HPFS",
    0x0B to "FAT32 (CHS)",
    0x0C to "FAT32 (LBA)",
    0x0E to "FAT16 (LBA)",
    0x0F to "Extended (LBA)",
    0x11 to "Hidden FAT12",
    0x14 to "Hidden FAT16 (<32 MB)",
    0x16 to "Hidden FAT16",
    0x17 to "Hidden NTFS/HPFS",
    0x1B to "Hidden FAT32 (CHS)",
    0x1C to "Hidden FAT32 (LBA)",
    0x1E to "Hidden FAT16 (LBA)",
    0x82 to "Linux swap",
    0x83 to "Linux (ext4/others)",
    0x85 to "Linux extended",
    0x8E to "Linux LVM",
    0xEE to "GPT protective",
    0xEF to "EFI System",
    0xFD to "Linux RAID",
)

private const val GPT_SIGNATURE_HEX = "4546492050415254"
private const val GPT_HEADER_OFFSET = 512L
private const val MAX_GPT_ENTRIES = 128

// https://github.com/arvidjaar/bootinfoscript/blob/master/bootinfoscript#L916
// yes a source
private val GPT_TYPE_GUIDS = mapOf(
    "28732ac11ff8d211ba4b00a0c93ec93b" to "EFI System",
    "af3dc60f838472478e793d69d8477de4" to "Linux",
    "a2a0d0ebe5b9334487c068b6b72699c7" to "Microsoft basic data",
    "6dfd5706aba4c44384e50933c84b4f4f" to "Linux swap",
    "79d3d6e607f5c244a23c238f2a3df928" to "Linux LVM",
    "0f889da1fc053b4da006743f0f84911e" to "Linux RAID",
)

data class PartitionEntry(
    val index: Int, // 1-based
    val bootable: Boolean,
    val typeId: Int,
    val typeName: String,
    val startLBA: Long, // sector offset
    val sizeSectors: Long,
    val offsetBytes: Long, // startLBA * 512
    val sizeBytes: Long, // sizeSectors * 512
    val detectedFs: FsType? = null,
    val detectedFsName: String? = null,
    val label: String? = null,
)

enum class PartitionScheme { MBR, GPT }

fun fsDisplayName(
    ctx: Context,
    fs: FsType?,
    imagePath: String? = null,
    baseOffset: Long = 0L,
): String =
    when (fs) {
        FsType.EXT4 -> "ext4"
        FsType.VFAT -> detectFatVariant(imagePath, baseOffset) ?: "FAT"
        FsType.EXFAT -> "exFAT"
        FsType.ISO9660 -> "ISO9660"
        is FsType.OTHER -> fs.name
        null -> ctx.getString(R.string.image_type_raw)
    }

data class PartitionTableInfo(
    val partitions: List<PartitionEntry>,
    val totalSizeBytes: Long,
    val scheme: PartitionScheme = PartitionScheme.MBR,
)

// check whether an image is a partitioned disk, returns null if not partitioned
fun probePartitionTable(ctx: Context, imagePath: String): PartitionTableInfo? {
    fun hexAt(skip: Int, count: Int, baseOffset: Long = 0L) =
        hexProbe(imagePath, skip, count, baseOffset)

    // MBR signature required for both MBR and GPT (protective MBR)
    if (hexAt(510, 2) != FAT_SIG_HEX) return null

    // rule out bare FAT/exFAT filesystems that also carry 55AA
    val bps = hexAt(11, 2)
    val nFats = hexAt(16, 1)
    if (bps in VALID_FAT_BPS && nFats in VALID_FAT_NFATS) return null
    if (hexAt(3, 5) == EXFAT_OEM_HEX) return null // exFAT OEM ID

    // check part scheme
    val isGPT = hexAt(0, 8, GPT_HEADER_OFFSET) == GPT_SIGNATURE_HEX
    val (entries, scheme) = if (isGPT) {
        parseGPTEntries(ctx, ::hexAt) ?: return null
    } else {
        parseMBREntries(ctx, ::hexAt) ?: return null
    }

    // validate partition boundaries against actual image size
    val totalSize = queryImageSize(imagePath)
    if (totalSize > 0) {
        entries.removeAll { p ->
            val end = p.offsetBytes + p.sizeBytes
            if (p.offsetBytes < 0 || p.sizeBytes < 0 || end < 0 || p.offsetBytes > totalSize) {
                Log.w(
                    TAG, "P${p.index}: partition boundaries invalid or exceed image size, skipping"
                )
                true
            } else false
        }
    }
    if (entries.isEmpty()) return null
    Log.d(TAG, "${scheme.name} image: ${entries.size} partition(s), total=$totalSize")
    entries.forEach { p ->
        Log.d(
            TAG,
            "  P${p.index}: type=${p.typeName}, start=${p.startLBA}, size=${p.sizeSectors} sectors (${p.sizeBytes} bytes)"
        )
    }
    return PartitionTableInfo(partitions = entries, totalSizeBytes = totalSize, scheme = scheme)
}

fun probePartitionFilesystems(
    ctx: Context, imagePath: String, partitions: List<PartitionEntry>
): List<PartitionEntry> {
    return partitions.map { part ->
        val offset = part.offsetBytes
        fun probe(skip: Int, count: Int) = hexProbe(imagePath, skip, count, offset)

        val detected = probeFsMagic(::probe, part.sizeBytes)
        val rawLabel =
            (if (detected != null) probeLabel(::probe, detected, part.sizeBytes) else null)
                ?: part.label
        val label = labelToMountStem(rawLabel)
        if (detected != null) {
            val typeName = fsDisplayName(ctx, detected, imagePath, offset)
            Log.d(
                TAG,
                "P${part.index}: ${detected.mountType}" + if (label != null) " label='$label'" else ""
            )
            part.copy(
                detectedFs = detected,
                detectedFsName = detected.mountType,
                typeName = typeName,
                label = label
            )
        } else {
            Log.d(
                TAG,
                "P${part.index}: unknown fs (type=0x${"%02X".format(part.typeId)})"
            )
            part
        }
    }
}

// query image file size directly; returns 0 when the size can't be determined
private fun queryImageSize(imagePath: String): Long = File(imagePath).length()

// parse MBR partition entries from the table at offset 446
private fun parseMBREntries(
    ctx: Context,
    hexAt: (skip: Int, count: Int, baseOffset: Long) -> String,
): Pair<MutableList<PartitionEntry>, PartitionScheme>? {
    val rawHex = hexAt(446, 64, 0L)
    if (rawHex.length < 128) {
        Log.w(TAG, "short partition table read: ${rawHex.length} hex chars")
        return null
    }
    val entries = mutableListOf<PartitionEntry>()
    for (i in 0 until 4) {
        val base = i * 32  // 16 bytes = 32 hex chars
        val entry = rawHex.substring(base, base + 32)
        val status: Int
        val typeId: Int
        val startLBA: Long
        val sizeSectors: Long
        try {
            status = entry.take(2).toInt(16)
            typeId = entry.substring(8, 10).toInt(16)
            startLBA = parseLeHexAt(entry, byteOffset = 8, byteCount = 4)
            sizeSectors = parseLeHexAt(entry, byteOffset = 12, byteCount = 4)
        } catch (e: NumberFormatException) {
            Log.w(TAG, "Malformed partition entry $i: ${e.message}")
            continue
        } catch (e: IndexOutOfBoundsException) {
            Log.w(TAG, "Truncated partition entry $i: ${e.message}")
            continue
        }
        if (typeId == 0 || sizeSectors == 0L) continue
        if (startLBA > Long.MAX_VALUE / 512 || sizeSectors > Long.MAX_VALUE / 512) {
            Log.w(TAG, "Partition $i: sector values too large, skipping")
            continue
        }
        val typeName = PARTITION_TYPE_NAMES[typeId] ?: ctx.getString(
            R.string.partition_type_unknown_hex, "%02X".format(typeId)
        )
        entries += PartitionEntry(
            index = i + 1,
            bootable = status == 0x80,
            typeId = typeId,
            typeName = typeName,
            startLBA = startLBA,
            sizeSectors = sizeSectors,
            offsetBytes = startLBA * 512,
            sizeBytes = sizeSectors * 512,
        )
    }
    if (entries.isEmpty()) return null
    return entries to PartitionScheme.MBR
}

// parse GPT partition entries from the header at LBA 1
private fun parseGPTEntries(
    ctx: Context,
    hexAt: (skip: Int, count: Int, baseOffset: Long) -> String,
): Pair<MutableList<PartitionEntry>, PartitionScheme>? {
    val headerFields = hexAt(72, 16, GPT_HEADER_OFFSET)
    if (headerFields.length < 32) {
        Log.w(TAG, "short GPT header read: ${headerFields.length} hex chars")
        return null
    }
    val entriesStartLBA = parseLeHexAt(headerFields, byteCount = 8)
    val numEntries = parseLeHexAt(headerFields, byteOffset = 8, byteCount = 4).toInt()
    val entrySize = parseLeHexAt(headerFields, byteOffset = 12, byteCount = 4).toInt()
    if (entrySize !in 128..4096 || numEntries <= 0) {
        Log.w(TAG, "invalid GPT header: entrySize=$entrySize, numEntries=$numEntries")
        return null
    }
    val entriesOffset = entriesStartLBA * 512
    val readCount = minOf(numEntries, MAX_GPT_ENTRIES)
    val totalReadBytes = readCount * entrySize
    if (entriesOffset < 0 || entriesOffset > 1024L * 1024 * 1024) {
        Log.w(TAG, "GPT entry table offset implausible: $entriesOffset")
        return null
    }
    val rawHex = hexAt(0, totalReadBytes, entriesOffset)
    val entryHexLen = entrySize * 2

    val entries = mutableListOf<PartitionEntry>()
    for (i in 0 until readCount) {
        val base = i * entryHexLen
        if (base + 96 > rawHex.length) break
        val typeGuid = rawHex.substring(base, base + 32)
        if (typeGuid.all { it == '0' }) continue
        val startLBA = parseLeHexAt(rawHex, i * entrySize + 32, byteCount = 8)
        val endLBA = parseLeHexAt(rawHex, i * entrySize + 40, byteCount = 8)
        if (startLBA !in 1..endLBA) continue
        val sizeSectors = endLBA - startLBA + 1
        if (startLBA > Long.MAX_VALUE / 512 || sizeSectors > Long.MAX_VALUE / 512) {
            Log.w(TAG, "GPT P${i + 1}: sector values too large, skipping")
            continue
        }
        val guidName = GPT_TYPE_GUIDS[typeGuid] ?: ctx.getString(R.string.partition_type_unknown)
        val partName = if (base + entryHexLen <= rawHex.length) parseGPTName(
            rawHex, base + 112, base + entryHexLen
        ) else null
        val typeName =
            if (!partName.isNullOrBlank() && partName != guidName) "$guidName ($partName)" else guidName
        entries += PartitionEntry(
            index = entries.size + 1,
            bootable = false,
            typeId = 0,
            typeName = typeName,
            startLBA = startLBA,
            sizeSectors = sizeSectors,
            offsetBytes = startLBA * 512,
            sizeBytes = sizeSectors * 512,
        )
    }
    if (entries.isEmpty()) return null
    return entries to PartitionScheme.GPT
}

// decode a UTF-16LE partition name from GPT entry hex data
private fun parseGPTName(hex: String, start: Int, end: Int): String? {
    val sb = StringBuilder()
    var i = start
    while (i + 4 <= end) {
        val lo = hex.substring(i, i + 2).toIntOrNull(16) ?: break
        val hi = hex.substring(i + 2, i + 4).toIntOrNull(16) ?: break
        val cp = (hi shl 8) or lo
        if (cp == 0) break
        sb.append(cp.toChar())
        i += 4
    }
    return sb.toString().trim().takeIf { it.isNotBlank() }
}
