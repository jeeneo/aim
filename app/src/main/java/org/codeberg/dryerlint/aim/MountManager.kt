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

package org.codeberg.dryerlint.aim

import android.content.Context
import android.util.Log
import org.codeberg.dryerlint.aim.utils.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class MountMode { LOCAL, PUBLIC }

enum class FsType(val mountType: String, val posixPermissions: Boolean, val readOnly: Boolean = false) {
    EXT4("ext4", true),
    VFAT("vfat", false),
    EXFAT("exfat", false),
    ISO9660("iso9660", false, readOnly = true),
}

data class MountedImage(
    val devicePath: String,
    val mountPoint: String,
    val loopDevice: String,
    val fsType: FsType = FsType.EXT4,
)

typealias OpResult = Result<String>

// returned when mountImage detects a partitioned disk
data class PartitionedImageResult(
    val tableInfo: PartitionTableInfo,
    val partitions: List<PartitionEntry>, // with detectedFs filled in
)

data class EnvironmentStatus(
    val rootAvailable: Boolean = false,
    val rootMessage: String = "Not checked",
    val busyboxAvailable: Boolean = false,
    val busyboxPath: String = "",
    val busyboxMessage: String = "Not checked",
    val ready: Boolean = false,
)

fun generateMountStem(
    imagePath: String,
    allImagePaths: List<String>,
    diskLabel: String? = null,
    allLabels: Map<String, String?> = emptyMap(),
): String {
    if (diskLabel != null && isValidLabelStem(diskLabel)) {
        val labelConflict = allLabels.any { (path, label) ->
            path != imagePath && label != null && isValidLabelStem(label)
                    && label == diskLabel
        }
        if (!labelConflict) return diskLabel
    }
    fun sanitize(path: String) = filenameToMountStem(
        File(path).nameWithoutExtension
    )
    val sanitized = sanitize(imagePath)
    val conflict = allImagePaths.any { it != imagePath && sanitize(it) == sanitized }
    if (!conflict) return sanitized
    val h = File(imagePath).nameWithoutExtension.hashCode().toUInt()
    return String.format("%04X-%04X", (h.toInt() shr 16) and 0xFFFF, h.toInt() and 0xFFFF)
}

class MountManager(appContext: Context) {
    private val ctx = appContext.applicationContext
    val mountsDir: String = File(ctx.filesDir, "mounts").apply { mkdirs() }.absolutePath
    private val maxMounts = 10
    private var busyboxBin = ""

    private val _mountedImages = MutableStateFlow<List<MountedImage>>(emptyList())
    val mountedImages: StateFlow<List<MountedImage>> = _mountedImages.asStateFlow()

    private val _envStatus = MutableStateFlow(EnvironmentStatus())
    val envStatus: StateFlow<EnvironmentStatus> = _envStatus.asStateFlow()

    fun checkEnvironment(): EnvironmentStatus {
        val (status, bb) = checkEnv(busyboxBin)
        busyboxBin = bb
        _envStatus.value = status
        return status
    }

    fun refreshMountedImages() {
        val r = RootShell.cmd("grep", ShellArg.literal("-F"),
            ShellArg.of("$mountsDir/"), pathArg("/proc/mounts"),
            ignoreError = true)
        _mountedImages.value = if (r.exitCode != 0 || r.output.isBlank()) emptyList()
        else r.output.lineSequence().mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"))
            val fs = p.getOrNull(2)?.let { FS_LIST[it] }
            if (p.size < 3 || fs == null || "loop" !in p[0]) null
            else MountedImage(p[0], p[1], p[0], fs)
        }.distinctBy { "${it.mountPoint}|${it.loopDevice}|${it.devicePath}" }
            .sortedBy { it.mountPoint }.toList()
    }

    fun mountImage(path: String, mode: MountMode = MountMode.LOCAL, mountDirName: String? = null): OpResult {
        if (!_envStatus.value.ready) return OpResult.failure(Exception("Environment not ready"))
        refreshMountedImages()
        if (_mountedImages.value.size >= maxMounts) return OpResult.failure(Exception("Mount limit reached ($maxMounts). Unmount an image first."))

        val imagePath = path.trim()
        if (imagePath.isBlank()) return OpResult.failure(Exception("Empty path"))
        val isIso = imagePath.endsWith(".iso", ignoreCase = true)
        if (!imagePath.endsWith(".img", ignoreCase = true) && !isIso) return OpResult.failure(Exception("Only .img and .iso files supported"))
        val imageFile = File(imagePath)
        if (!imageFile.exists()) return OpResult.failure(Exception("Image not found: $imagePath"))
        if (!validatePath(imagePath)) return OpResult.failure(Exception("Image path contains invalid characters"))

        val imgArg = pathArg(imagePath)

        // reject sparse images, not attempted for now (skip for ISOs)
        if (!isIso && RootShell.cmd("hexdump",
                ShellArg.literal("-C"), ShellArg.literal("-n"), ShellArg.literal("20000"), imgArg,
                busyboxBin = busyboxBin,
                pipeInto = TrustedCmdFragment.of("grep '3a ff 26 ed'")
            ).let { it.exitCode == 0 && it.output.isNotBlank() }) return OpResult.failure(Exception("Sparse images not supported"))

        val fsType: FsType
        try {
            fsType = detectFilesystem(imagePath, busyboxBin) ?: run {
                Log.w("MountManager", "fs detection failed for $imagePath (${imageFile.length()} bytes)")
                return OpResult.failure(Exception("Unsupported or unrecognised filesystem (expected ext4, FAT32, exFAT, ISO9660)."))
            }
        } catch (e: PartitionedImageException) {
            Log.d("MountManager", "Partitioned image detected for $imagePath")
            val withFs = probePartitionFilesystems(imagePath, e.tableInfo.partitions, busyboxBin)
            _pendingPartitionResult = PartitionedImageResult(e.tableInfo, withFs)
            return OpResult.failure(e)
        }
        Log.d("MountManager", "Detected: ${fsType.mountType} for $imagePath")
        return mountCheckedImage(imagePath, imageFile, fsType, mode, mountDirName)
    }

    // mount a partition from a partitioned disk image (called after user picks one)
    fun mountPartition(
        path: String,
        partition: PartitionEntry,
        mode: MountMode = MountMode.LOCAL,
        mountDirName: String? = null,
    ): OpResult {
        if (!_envStatus.value.ready) return OpResult.failure(Exception("Environment not ready"))
        refreshMountedImages()
        if (_mountedImages.value.size >= maxMounts) return OpResult.failure(Exception("Mount limit reached ($maxMounts). Unmount an image first."))
        val imagePath = path.trim()
        val imageFile = File(imagePath)
        val fsType = partition.detectedFs ?: return OpResult.failure(Exception("No supported filesystem detected on partition ${partition.index}"))
        val stem = mountDirName ?: filenameToMountStem(imageFile.nameWithoutExtension + "_p${partition.index}")
        return performMount(imagePath, stem, fsType, mode, partition.offsetBytes, partition.sizeBytes)
    }

    // holds the partition table from the last PartitionedImageException
    private var _pendingPartitionResult: PartitionedImageResult? = null
    val pendingPartitionResult: PartitionedImageResult? get() = _pendingPartitionResult

    // probe partition table and detect filesystems for each partition (for re-mounting with stored selection)
    fun probePartitions(path: String): PartitionedImageResult? {
        val table = probePartitionTable(path, busyboxBin) ?: return null
        val withFs = probePartitionFilesystems(path, table.partitions, busyboxBin)
        return PartitionedImageResult(table, withFs)
    }

    private fun mountCheckedImage(
        imagePath: String,
        imageFile: File,
        fsType: FsType,
        mode: MountMode,
        mountDirName: String?,
    ): OpResult {
        val imgArg = pathArg(imagePath)
        // already loop-attached?
        if (RootShell.cmd("losetup", ShellArg.literal("-a"),
                busyboxBin = busyboxBin, suppressErr = true,
                pipeInto = TrustedCmdFragment.of("grep -F ${imgArg.quoted}")
            ).let { it.exitCode == 0 && it.output.isNotBlank() }) {
            refreshMountedImages()
            return OpResult.failure(Exception("This image is already mounted"))
        }
        val stem = mountDirName ?: filenameToMountStem(imageFile.nameWithoutExtension)
        return performMount(imagePath, stem, fsType, mode)
    }

    private fun performMount(
        imagePath: String,
        stem: String,
        fsType: FsType,
        mode: MountMode,
        partOffset: Long = 0,
        partSize: Long = 0,
    ): OpResult {
        val mp = "$mountsDir/$stem"
        val opts = buildMountOpts(fsType, mode)
        // already mounted at this mount point?
        val mpArg = pathArg(mp)
        val alreadyMounted = RootShell.cmd("awk",
            ShellArg.literal("-v"), ShellArg.literal("mp=${mpArg.quoted}"),
            ShellArg.literal($$"$2==mp {print \"OK\"; exit}"),
            pathArg("/proc/mounts"),
            busyboxBin = busyboxBin)
        if (alreadyMounted.exitCode == 0 && "OK" in alreadyMounted.output) {
            refreshMountedImages()
            return OpResult.success("Already mounted at $mp")
        }
        val result = doMount(imagePath, mp, fsType, opts, busyboxBin, partOffset, partSize)
        if (result.isSuccess) {
            if (fsType.posixPermissions) makeAccessible(mp, mountsDir, busyboxBin)
            refreshMountedImages()
            ImageProvider.notifyRootsChanged(ctx)
        }
        return result
    }

    fun unmountImage(item: MountedImage): OpResult {
        if (!item.mountPoint.startsWith("$mountsDir/")) return OpResult.failure(Exception("Invalid mount point"))
        if (!validatePath(item.mountPoint) || !validatePath(item.loopDevice)) return OpResult.failure(Exception("Path contains invalid characters"))
        val mpArg = pathArg(item.mountPoint)
        val loopArg = loopDevArg(item.loopDevice)
        if (item.fsType.posixPermissions) restorePermissions(item.mountPoint, busyboxBin)
        RootShell.cmd("fuser", ShellArg.literal("-km"), mpArg, busyboxBin = busyboxBin, suppressErr = true, ignoreError = true)
        // try busybox umount first, then system umount
        val umountResult = RootShell.cmd("umount", mpArg, busyboxBin = busyboxBin)
        if (umountResult.exitCode != 0) {
            val fallback = RootShell.cmd("umount", mpArg)
            if (fallback.exitCode != 0) return OpResult.failure(Exception("Unmount failed"))
        }
        RootShell.cmd("losetup", ShellArg.literal("-d"), loopArg, busyboxBin = busyboxBin, suppressErr = true, ignoreError = true)
        RootShell.cmd("rmdir", mpArg, suppressErr = true, ignoreError = true)
        refreshMountedImages()
        ImageProvider.notifyRootsChanged(ctx)
        return OpResult.success("Unmounted ${item.mountPoint}")
    }

    fun formatImage(path: String): OpResult = formatImage(path, _envStatus.value.ready, busyboxBin)

    fun createStorageBind(stem: String, bindDir: String): OpResult {
        if (!_envStatus.value.ready) return OpResult.failure(Exception("Environment not ready"))
        validateBindDir(bindDir)?.let { return OpResult.failure(Exception(it)) }
        val resolvedBindDir = try {
            File(bindDir).canonicalPath
        } catch (e: Exception) {
            return OpResult.failure(Exception("Could not resolve bind directory: ${e.message}"))
        }
        validateBindDir(resolvedBindDir)?.let {
            return OpResult.failure(Exception("Resolved bind directory rejected: $it"))
        }
        val verifiedBindDir = try {
            File(bindDir).canonicalPath
        } catch (_: Exception) {
            return OpResult.failure(Exception("Path changed during validation"))
        }
        if (verifiedBindDir != resolvedBindDir) {
            return OpResult.failure(Exception("Bind directory path changed between validation and use"))
        }
        val safeStem = sanitizeStem(stem)
        val source = "$mountsDir/$safeStem"
        val target = "$resolvedBindDir/$safeStem"
        if (!validatePath(source) || !validatePath(target) || !validatePath(resolvedBindDir)) return OpResult.failure(Exception("Path contains invalid characters"))
        val srcArg = pathArg(source)
        val tgtArg = pathArg(target)
        val dirArg = pathArg(resolvedBindDir)
        // check if the parent directory exists with media_rw ownership so it's visible via FUSE
        val mkdirDir = RootShell.cmd("mkdir", ShellArg.literal("-p"), dirArg, chain = TrustedCmdFragment.of("chown 1023:1023 ${dirArg.quoted} && chmod 775 ${dirArg.quoted}"))
        if (mkdirDir.exitCode != 0) return OpResult.failure(Exception("Failed to create storage directory: $resolvedBindDir"))
        // check if already bind-mounted
        if (RootShell.cmd("grep", ShellArg.literal("-qF"), ShellArg.of(" $target "), pathArg("/proc/mounts")).exitCode == 0) return OpResult.success("Already exposed at $target")
        // create mount point directory
        val mkdirTgt = RootShell.cmd("mkdir", ShellArg.literal("-p"), tgtArg, chain = TrustedCmdFragment.of("chown 1023:1023 ${tgtArg.quoted} && chmod 775 ${tgtArg.quoted}"))
        if (mkdirTgt.exitCode != 0) return OpResult.failure(Exception("Failed to create mount point: $target"))
        // bind mount
        val r = RootShell.cmd("mount", ShellArg.literal("--bind"), srcArg, tgtArg, redirectErr = true)
        if (r.exitCode != 0) return OpResult.failure(Exception("Bind mount failed: ${r.output}"))
        // set SELinux context so media layer can traverse it
        RootShell.cmd("chcon", ShellArg.literal("-R"), secontextArg("u:object_r:media_rw_data_file:s0"), tgtArg, suppressErr = true, ignoreError = true)
        return OpResult.success("Exposed at $target")
    }

    // unmount a bind mount at [bindDir]/[stem] and remove the empty directory
    fun removeStorageBind(stem: String, bindDir: String): OpResult {
        validateBindDir(bindDir)?.let { return OpResult.failure(Exception(it)) }
        val resolvedBindDir = try {
            File(bindDir).canonicalPath
        } catch (e: Exception) {
            return OpResult.failure(Exception("Could not resolve bind directory: ${e.message}"))
        }
        validateBindDir(resolvedBindDir)?.let {
            return OpResult.failure(Exception("Resolved bind directory rejected: $it"))
        }
        // re-resolve to detect path manipulation between validation and use
        val verifiedBindDir = try {
            File(bindDir).canonicalPath
        } catch (_: Exception) {
            return OpResult.failure(Exception("Path changed during validation"))
        }
        if (verifiedBindDir != resolvedBindDir) {
            return OpResult.failure(Exception("Bind directory path changed between validation and use"))
        }
        val safeStem = sanitizeStem(stem)
        val target = "$resolvedBindDir/$safeStem"
        if (!validatePath(target)) return OpResult.failure(Exception("Path contains invalid characters"))
        val tgtArg = pathArg(target)
        // unmount if mounted
        val isMounted = RootShell.cmd("grep", ShellArg.literal("-qF"), ShellArg.of(" $target "), pathArg("/proc/mounts"))
        if (isMounted.exitCode == 0) {
            RootShell.cmd("umount", tgtArg, suppressErr = true, ignoreError = true)
        }
        // clean up empty directory
        RootShell.cmd("rmdir", tgtArg, suppressErr = true, ignoreError = true)
        return OpResult.success("Storage mount removed: $target")
    }
}
