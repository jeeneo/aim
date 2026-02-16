/**
* Copyright (C) 2026 dryerlint <codeberg.org/dryerlint>
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package org.codeberg.dryerlint.aim.utils

import android.util.Log
import org.codeberg.dryerlint.aim.FsType

private const val TAG = "PartitionTable"
private val PARTITION_TYPE_NAMES = mapOf(
    0x01 to "FAT12",
    0x04 to "FAT16 (<32 MB)",
    0x05 to "Extended",
    0x06 to "FAT16",
    0x07 to "exFAT", // NTFS/exFAT/HPFS, exFAT only supported like fr
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
    val label: String? = null,
)

enum class PartitionScheme { MBR, GPT }

data class PartitionTableInfo(
    val partitions: List<PartitionEntry>,
    val totalSizeBytes: Long,
    val scheme: PartitionScheme = PartitionScheme.MBR,
)

// check whether an image is a partitioned disk (MBR), returns null if not partitioned
fun probePartitionTable(imagePath: String, busyboxBin: String): PartitionTableInfo? {
    val imgArg = pathArg(imagePath)
    fun hexAt(skip: Int, count: Int) = hexProbe(imagePath, skip, count, busyboxBin, imgArg)
    val sig = hexAt(510, 2)
    if (sig != "55aa") return null
    val bps = hexAt(11, 2)
    val nFats = hexAt(16, 1)
    if (bps in VALID_FAT_BPS && nFats in VALID_FAT_NFATS) return null
    val oem = hexAt(3, 5)
    if (oem == "4558464154") return null  // exFAT
    probeGPT(imagePath, busyboxBin, imgArg)?.let { return it }
    val rawHex = hexAt(446, 64)
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
            startLBA = parseL3U32(entry, 16)
            sizeSectors = parseL3U32(entry, 24)
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
        val typeName = PARTITION_TYPE_NAMES[typeId] ?: "Unknown (0x${"%02X".format(typeId)})"
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
    val totalSize = queryImageSize(busyboxBin, imgArg)
    if (totalSize > 0) {
        entries.removeAll { p ->
            val end = p.offsetBytes + p.sizeBytes
            if (p.offsetBytes < 0 || p.sizeBytes < 0 || end < 0 || p.offsetBytes > totalSize) {
                Log.w(TAG, "P${p.index}: partition boundaries invalid or exceed image size, skipping")
                true
            } else false
        }
    }
    Log.d(TAG, "Partitioned image: ${entries.size} partition(s), total=${totalSize}")
    entries.forEach { p ->
        Log.d(TAG, "  P${p.index}: type=${p.typeName}, start=${p.startLBA}, size=${p.sizeSectors} sectors (${p.sizeBytes} bytes)")
    }
    return PartitionTableInfo(
        partitions = entries,
        totalSizeBytes = totalSize,
        scheme = PartitionScheme.MBR,
    )
}

fun probePartitionFilesystems(imagePath: String, partitions: List<PartitionEntry>, busyboxBin: String): List<PartitionEntry> {
    val imgArg = pathArg(imagePath)
    return partitions.map { part ->
        val offset = part.offsetBytes
        fun probe(skip: Int, count: Int) = hexProbe(imagePath, skip, count, busyboxBin, imgArg, offset)
        val detected = detectFsByMagic(::probe, part.sizeBytes)
        val rawLabel = (if (detected != null) probeLabel(::probe, detected, part.sizeBytes) else null) ?: part.label
        val label = labelToMountStem(rawLabel)
        if (detected != null) {
            Log.d(TAG, "P${part.index}: ${detected.mountType}" + if (label != null) " label='$label'" else "")
            part.copy(detectedFs = detected, label = label)
        } else {
            Log.d(TAG, "P${part.index}: unknown fs (type=0x${"%02X".format(part.typeId)})")
            part
        }
    }
}

// query image file size via stat or wc -c fallback
private fun queryImageSize(busyboxBin: String, imgArg: ShellArg): Long {
    val sizeOut = RootShell.cmd("stat",
        ShellArg.literal("-c"), ShellArg.literal("%s"), imgArg,
        busyboxBin = busyboxBin, suppressErr = true,
        orChain = if (busyboxBin.isNotEmpty()) {
            TrustedCmdFragment.of("'" + busyboxBin.replace("'", "'\\''") + "' wc -c < ${imgArg.quoted}")
        } else {
            TrustedCmdFragment.of("busybox wc -c < ${imgArg.quoted}")
        }
    )
    return sizeOut.output.trim().toLongOrNull() ?: 0L
}

// check for a GPT partition table in the image. returns null if not GPT.
private fun probeGPT(imagePath: String, busyboxBin: String, imgArg: ShellArg): PartitionTableInfo? {
    fun hexAt(skip: Int, count: Int, baseOffset: Long = 0L) = hexProbe(imagePath, skip, count, busyboxBin, imgArg, baseOffset)
    if (hexAt(0, 8, GPT_HEADER_OFFSET) != GPT_SIGNATURE_HEX) return null
    val headerFields = hexAt(72, 16, GPT_HEADER_OFFSET)
    if (headerFields.length < 32) {
        Log.w(TAG, "short GPT header read: ${headerFields.length} hex chars")
        return null
    }
    val entriesStartLBA = parseL3U64(headerFields, 0)
    val numEntries = parseL3U32(headerFields, 16).toInt()
    val entrySize = parseL3U32(headerFields, 24).toInt()
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
        val startLBA = parseL3U64(rawHex, base + 64)
        val endLBA = parseL3U64(rawHex, base + 80)
        if (startLBA !in 1..endLBA) continue
        val sizeSectors = endLBA - startLBA + 1
        if (startLBA > Long.MAX_VALUE / 512 || sizeSectors > Long.MAX_VALUE / 512) {
            Log.w(TAG, "GPT P${i + 1}: sector values too large, skipping")
            continue
        }
        val guidName = GPT_TYPE_GUIDS[typeGuid] ?: "Unknown"
        val partName = if (base + entryHexLen <= rawHex.length) parseGPTName(rawHex, base + 112, base + entryHexLen) else null
        val typeName = if (!partName.isNullOrBlank() && partName != guidName)
        "$guidName ($partName)" else guidName
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
    val totalSize = queryImageSize(busyboxBin, imgArg)
    if (totalSize > 0) {
        entries.removeAll { p ->
            val end = p.offsetBytes + p.sizeBytes
            if (p.offsetBytes < 0 || p.sizeBytes < 0 || end < 0 || p.offsetBytes > totalSize) {
                Log.w(TAG, "GPT P${p.index}: partition boundaries exceed image size, skipping")
                true
            } else false
        }
    }
    Log.d(TAG, "GPT image: ${entries.size} partition(s), total=$totalSize")
    entries.forEach { p ->
        Log.d(TAG, "  P${p.index}: type=${p.typeName}, start=${p.startLBA}, size=${p.sizeSectors} sectors (${p.sizeBytes} bytes)")
    }
    return PartitionTableInfo(partitions = entries, totalSizeBytes = totalSize, scheme = PartitionScheme.GPT)
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

// parse a 4-byte little-endian unsigned integer from a hex string at the given hex-char offset
private fun parseL3U32(hex: String, hexOffset: Int): Long {
    val b0 = hex.substring(hexOffset, hexOffset + 2).toLong(16)
    val b1 = hex.substring(hexOffset + 2, hexOffset + 4).toLong(16)
    val b2 = hex.substring(hexOffset + 4, hexOffset + 6).toLong(16)
    val b3 = hex.substring(hexOffset + 6, hexOffset + 8).toLong(16)
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}

// parse an 8-byte little-endian unsigned integer from a hex string at the given hex-char offset
private fun parseL3U64(hex: String, hexOffset: Int): Long {
    var result = 0L
    for (i in 0 until 8) {
        val byteVal = hex.substring(hexOffset + i * 2, hexOffset + i * 2 + 2).toLong(16)
        result = result or (byteVal shl (i * 8))
    }
    return result
}
