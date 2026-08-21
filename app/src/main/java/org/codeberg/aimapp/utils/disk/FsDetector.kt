// SPDX-License-Identifier: GPL-3.0-or-later

package org.codeberg.aimapp.utils.disk

import android.content.Context
import android.util.Log
import org.codeberg.aimapp.utils.mounts.FsType
import org.codeberg.aimapp.utils.mounts.PartitionTableInfo
import org.codeberg.aimapp.utils.mounts.probePartitionTable
import org.codeberg.aimapp.utils.parseLeHexAt
import org.codeberg.aimapp.utils.shell.RootShell
import org.codeberg.aimapp.utils.shell.ShellArg
import org.codeberg.aimapp.utils.shell.pathArg
import org.codeberg.aimapp.utils.shell.resolvedBusyboxPath
import java.io.File
import java.io.RandomAccessFile

private const val TAG = "FsDetector"

// valid FAT bytes-per-sector values (little-endian hex of 512, 1024, 2048, 4096)
internal val VALID_FAT_BPS = setOf("0002", "0004", "0008", "0010")
internal val VALID_FAT_NFATS = setOf("01", "02")
internal const val EXFAT_OEM_HEX = "4558464154" // "EXFAT"
internal const val FAT_SIG_HEX = "55aa"
private const val NTFS_OEM_HEX = "4e544653" // "NTFS"
private const val EXT4_MAGIC_HEX = "53ef"
private const val ISO_MAGIC_HEX = "4344303031" // "CD001"

internal val FS_MAP = mapOf(
    "ext4" to FsType.EXT4,
    "vfat" to FsType.VFAT,
    "exfat" to FsType.EXFAT,
    "iso9660" to FsType.ISO9660,
)

// read [count] bytes at [skip] (plus [baseOffset]) directly from the image and return
// them as lowercase hex pairs; empty string on any read failure
fun hexProbe(imagePath: String, skip: Int, count: Int, baseOffset: Long = 0): String {
    return try {
        RandomAccessFile(imagePath, "r").use { f ->
            f.seek(baseOffset + skip)
            val buf = ByteArray(count)
            var off = 0
            while (off < count) {
                val n = f.read(buf, off, count - off)
                if (n < 0) break
                off += n
            }
            buildString(off * 2) {
                for (i in 0 until off) append("%02x".format(buf[i].toInt() and 0xFF))
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "hexProbe($imagePath, skip=$skip, count=$count) failed: ${e.message}")
        ""
    }
}

// FAT12/16/32
fun detectFatVariant(imagePath: String?, baseOffset: Long = 0L): String? {
    if (imagePath == null) return null
    val probe = { skip: Int, count: Int -> hexProbe(imagePath, skip, count, baseOffset) }
    val bytesPerSector = parseLeHexAt(probe(11, 2), byteCount = 2)
    val sectorsPerCluster = parseLeHexAt(probe(13, 1), byteCount = 1)
    val reservedSectors = parseLeHexAt(probe(14, 2), byteCount = 2)
    val numFats = parseLeHexAt(probe(16, 1), byteCount = 1)
    val rootEntryCount = parseLeHexAt(probe(17, 2), byteCount = 2)
    val totalSectors16 = parseLeHexAt(probe(19, 2), byteCount = 2)
    val fatSize16 = parseLeHexAt(probe(22, 2), byteCount = 2)
    val totalSectors32 = parseLeHexAt(probe(32, 4), byteCount = 4)
    val fatSize32 =
        parseLeHexAt(probe(36, 4), byteCount = 4) // only meaningful past FAT16's BPB layout

    if (bytesPerSector == 0L || sectorsPerCluster == 0L) return null
    val rootDirSectors = ((rootEntryCount * 32) + (bytesPerSector - 1)) / bytesPerSector
    val fatSize = fatSize16.takeIf { it != 0L } ?: fatSize32
    val totalSectors = totalSectors16.takeIf { it != 0L } ?: totalSectors32
    if (fatSize == 0L || totalSectors == 0L) return null

    val dataSectors = totalSectors - (reservedSectors + numFats * fatSize + rootDirSectors)
    if (dataSectors <= 0) return null
    val clusterCount = dataSectors / sectorsPerCluster

    return when {
        clusterCount < 4085 -> "FAT12" // only relevant to floppies really
        clusterCount < 65525 -> "FAT16"
        else -> "FAT32"
    }
}

fun probeFsMagic(probe: (Int, Int) -> String, sizeBytes: Long = Long.MAX_VALUE): FsType? {
    return detectFsByMagic(probe, sizeBytes) ?: identifyUnsupportedFs(probe)
}

private fun detectFsByMagic(
    probe: (Int, Int) -> String, sizeBytes: Long = Long.MAX_VALUE
): FsType? {
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

fun identifyUnsupportedFs(probe: (Int, Int) -> String): FsType? {
    return if (probe(3, 4) == NTFS_OEM_HEX) FsType.OTHER("ntfs3") else null
}

class PartitionedImageException(val tableInfo: PartitionTableInfo) :
    Exception("Image contains a partition table")

// crude detect of a filesystems type for an image via blkid (preferred) or byte fallback.
// returns null for unsupported/unknown, returns the FsType for raw filesystem images.
// throws PartitionedImageException if the image is a partitioned disk.
sealed class DetectFsResult {
    data class Found(val fs: FsType) : DetectFsResult()
    object Unknown : DetectFsResult()
    data class AccessError(val reason: String, val exitCode: Int? = null) : DetectFsResult()
}

private fun summarizeOutput(out: String, max: Int = 160) =
    out.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        .let { if (it.length <= max) it else it.take(max) + "..." }

private fun tryBlkid(
    imgArg: ShellArg, busyboxBin: String, attemptNo: Int
): Pair<DetectFsResult.Found?, String> {
    val r = RootShell.cmd(
        "blkid",
        ShellArg.literal("-o"),
        ShellArg.literal("value"),
        ShellArg.literal("-s"),
        ShellArg.literal("TYPE"),
        imgArg,
        busyboxBin = busyboxBin,
        ignoreError = true
    )
    val norm = r.output.trim().lowercase()
    val summary = "blkid #$attemptNo: code=${r.exitCode}, type='${summarizeOutput(norm)}'"
    val found = if (r.exitCode == 0 && r.output.isNotBlank()) {
        DetectFsResult.Found(FS_MAP[norm] ?: FsType.OTHER(norm))
    } else null
    return found to summary
}

fun detectFilesystem(ctx: Context, imagePath: String): DetectFsResult {
    val imgFile = File(imagePath)
    if (!imgFile.exists()) return DetectFsResult.AccessError("file does not exist")
    val imgArg = pathArg(imagePath)
    val accessReason = try {
        RandomAccessFile(imagePath, "r").use { it.read() }
        null
    } catch (e: Exception) {
        e.message?.takeIf { it.isNotBlank() } ?: "read probe failed"
    }
    if (accessReason != null) {
        return DetectFsResult.AccessError(accessReason)
    }
    Log.d(TAG, "start for $imagePath")
    val blkidAttempts = mutableListOf<String>()

    // try system blkid first, then busybox's applet as fallback
    val attempts = buildList {
        add("")
        if (resolvedBusyboxPath.isNotEmpty()) add(resolvedBusyboxPath)
    }
    for ((i, bb) in attempts.withIndex()) {
        val (found, summary) = tryBlkid(imgArg, bb, i + 1)
        blkidAttempts += summary
        Log.d(TAG, summary)
        found?.let { return it }
    }

    fun probe(skip: Int, count: Int) = hexProbe(imagePath, skip, count)

    probeFsMagic(::probe)?.let { return DetectFsResult.Found(it) }

    // neither supported nor known-unsupported, check for partition table
    val fatSig = probe(510, 2)
    if (fatSig == FAT_SIG_HEX) {
        Log.w(TAG, "55AA boot sig found but BPB invalid - partitioned disk image?")
        probePartitionTable(ctx, imagePath)?.let { table ->
            throw PartitionedImageException(table)
        }
    }

    Log.w(TAG, "unsupported '$imagePath'. blkid=[${blkidAttempts.joinToString("; ")}]")
    return DetectFsResult.Unknown
}
