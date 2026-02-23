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

import android.content.Context
import android.util.Log
import org.codeberg.dryerlint.aim.FsType

private const val TAG = "FsDetector"

// valid FAT bytes-per-sector values (little-endian hex of 512, 1024, 2048, 4096)
internal val VALID_FAT_BPS = setOf("0002", "0004", "0008", "0010")
internal val VALID_FAT_NFATS = setOf("01", "02")
private const val EXFAT_OEM_HEX = "4558464154" // "EXFAT"
private const val NTFS_OEM_HEX = "4e544653" // "NTFS"
private const val EXT4_MAGIC_HEX = "53ef"
private const val FAT_SIG_HEX = "55aa"
private const val ISO_MAGIC_HEX = "4344303031" // "CD001"

val FS_LIST = mapOf(
    "ext4" to FsType.EXT4,
    "vfat" to FsType.VFAT,
    "exfat" to FsType.EXFAT,
    "iso9660" to FsType.ISO9660, // not really supported on most devices
)

fun hexProbe(
    imagePath: String,
    skip: Int,
    count: Int,
    busyboxBin: String,
    imgArg: ShellArg = pathArg(imagePath),
    baseOffset: Long = 0,
): String {
    val totalSkip = baseOffset + skip
    val ddResult = RootShell.cmd(
        "dd",
        ShellArg.literal("if=${imgArg.quoted}"),
        ShellArg.literal("bs=1"),
        ShellArg.literal("skip=$totalSkip"),
        ShellArg.literal("count=$count"),
        busyboxBin = busyboxBin,
        suppressErr = true,
        pipeInto = ShellCmd.of(
            "hexdump",
            ShellArg.literal("-v"),
            ShellArg.literal("-e"),
            ShellArg.of("/1 \"%02x\""),
            busyboxBin = busyboxBin
        )
    )
    return ddResult.output.trim().lowercase()
}

fun detectFsByMagic(probe: (Int, Int) -> String, sizeBytes: Long = Long.MAX_VALUE): FsType? {
    if (probe(1080, 2) == EXT4_MAGIC_HEX) return FsType.EXT4
    if (probe(510, 2) == FAT_SIG_HEX) {
        if (probe(3, 5) == EXFAT_OEM_HEX) return FsType.EXFAT
        val bps = probe(11, 2)
        val nFats = probe(16, 1)
        if (bps in VALID_FAT_BPS && nFats in VALID_FAT_NFATS) return FsType.VFAT
        return null
    }
    if (sizeBytes > 33000 && probe(32769, 5) == ISO_MAGIC_HEX) return FsType.ISO9660
    return null
}

// identify known-but-unsupported filesystems by magic bytes
fun identifyUnsupportedFs(probe: (Int, Int) -> String): String? {
    return if (probe(3, 4) == NTFS_OEM_HEX) "NTFS" else null
}

class PartitionedImageException(val tableInfo: PartitionTableInfo) :
    Exception("Image contains a partition table")

// crude detect of a filesystems type for an image via blkid (preferred) or byte fallback.
// returns null for unsupported/unknown, returns the FsType for raw filesystem images.
// throws PartitionedImageException if the image is a partitioned disk.
fun detectFilesystem(ctx: Context, imagePath: String, busyboxBin: String): FsType? {
    val imgArg = pathArg(imagePath)
    fun summarize(out: String, max: Int = 160) =
        out.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
            .let { if (it.length <= max) it else it.take(max) + "..." }
    Log.d(TAG, "start for $imagePath")
    val blkidAttempts = mutableListOf<String>()

    // blkid attempt 1: system blkid
    run {
        val r = RootShell.cmd(
            "blkid",
            ShellArg.literal("-o"),
            ShellArg.literal("value"),
            ShellArg.literal("-s"),
            ShellArg.literal("TYPE"),
            imgArg,
            suppressErr = true
        )
        val norm = r.output.trim().lowercase()
        blkidAttempts += "#1: code=${r.exitCode}, type='${summarize(norm)}'"
        Log.d(TAG, "blkid 1 exit=${r.exitCode}, out=${summarize(norm)}")
        if (r.exitCode == 0 && r.output.isNotBlank()) {
            FS_LIST[norm]?.let { return it }
            Log.w(TAG, "system blkid reported unsupported fs '$norm'")
            return null
        }
    }

    // blkid attempt 2: busybox blkid
    if (busyboxBin.isNotEmpty()) {
        val r = RootShell.cmd(
            "blkid",
            ShellArg.literal("-o"),
            ShellArg.literal("value"),
            ShellArg.literal("-s"),
            ShellArg.literal("TYPE"),
            imgArg,
            busyboxBin = busyboxBin,
            suppressErr = true
        )
        val norm = r.output.trim().lowercase()
        blkidAttempts += "#2: code=${r.exitCode}, type='${summarize(norm)}'"
        Log.d(TAG, "blkid 2 exit=${r.exitCode}, out=${summarize(norm)}")
        if (r.exitCode == 0 && r.output.isNotBlank()) {
            FS_LIST[norm]?.let { return it }
            Log.w(TAG, "busybox blkid reported unsupported fs '$norm'")
            return null
        }
    }

    fun probe(skip: Int, count: Int) = hexProbe(imagePath, skip, count, busyboxBin, imgArg)

    val result = detectFsByMagic(::probe)
    if (result != null) return result

    // detectFsByMagic returns null on 55AA with invalid BPB - check for partition table
    val fatSig = probe(510, 2)
    if (fatSig == "55aa") {
        Log.w(TAG, "55AA boot sig found but BPB invalid - partitioned disk image?")
        probePartitionTable(ctx, imagePath, busyboxBin)?.let { table ->
            throw PartitionedImageException(table)
        }
    }

    Log.w(TAG, "unsupported '$imagePath'. blkid=[${blkidAttempts.joinToString("; ")}]")
    return null
}
