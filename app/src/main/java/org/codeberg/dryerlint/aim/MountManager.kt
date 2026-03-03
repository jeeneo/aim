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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.codeberg.dryerlint.aim.utils.FS_LIST
import org.codeberg.dryerlint.aim.utils.PartitionEntry
import org.codeberg.dryerlint.aim.utils.PartitionTableInfo
import org.codeberg.dryerlint.aim.utils.PartitionedImageException
import org.codeberg.dryerlint.aim.utils.RootShell
import org.codeberg.dryerlint.aim.utils.ShellArg
import org.codeberg.dryerlint.aim.utils.ShellCmd
import org.codeberg.dryerlint.aim.utils.VALID_CHMOD_MODES
import org.codeberg.dryerlint.aim.utils.buildMountOpts
import org.codeberg.dryerlint.aim.utils.checkEnv
import org.codeberg.dryerlint.aim.utils.detectFilesystem
import org.codeberg.dryerlint.aim.utils.doMount
import org.codeberg.dryerlint.aim.utils.enumArg
import org.codeberg.dryerlint.aim.utils.filenameToMountStem
import org.codeberg.dryerlint.aim.utils.formatImage
import org.codeberg.dryerlint.aim.utils.isValidLabelStem
import org.codeberg.dryerlint.aim.utils.loopDevArg
import org.codeberg.dryerlint.aim.utils.makeAccessible
import org.codeberg.dryerlint.aim.utils.pathArg
import org.codeberg.dryerlint.aim.utils.probePartitionFilesystems
import org.codeberg.dryerlint.aim.utils.probePartitionTable
import org.codeberg.dryerlint.aim.utils.restorePermissions
import org.codeberg.dryerlint.aim.utils.sanitizeStem
import org.codeberg.dryerlint.aim.utils.secontextArg
import org.codeberg.dryerlint.aim.utils.validateBindDir
import org.codeberg.dryerlint.aim.utils.validatePath
import java.io.File

enum class MountMode { LOCAL, PUBLIC }

enum class FsType(
    val mountType: String, val posixPermissions: Boolean, val readOnly: Boolean = false
) {
    EXT4("ext4", true), VFAT("vfat", false), EXFAT("exfat", false), ISO9660(
        "iso9660", false, readOnly = true
    ),
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
    val rootMessage: String = "",
    val busyboxAvailable: Boolean = false,
    val busyboxPath: String = "",
    val busyboxMessage: String = "",
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
            path != imagePath && label != null && isValidLabelStem(label) && label == diskLabel
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

    private val _envStatus = MutableStateFlow(
        EnvironmentStatus(
            rootMessage = ctx.getString(R.string.env_not_checked),
            busyboxMessage = ctx.getString(R.string.env_not_checked),
        )
    )
    val envStatus: StateFlow<EnvironmentStatus> = _envStatus.asStateFlow()

    fun checkEnvironment(): EnvironmentStatus {
        val (status, bb) = checkEnv(ctx, busyboxBin)
        busyboxBin = bb
        _envStatus.value = status
        return status
    }

    fun refreshMountedImages() {
        val r = RootShell.cmd(
            "grep",
            ShellArg.literal("-F"),
            ShellArg.of("$mountsDir/"),
            pathArg("/proc/mounts"),
            ignoreError = true
        )
        Log.d("MountManager", "refreshMountedImages: grep exit=${r.exitCode}, output blank=${r.output.isBlank()}")
        Log.d("MountManager", "Filtered /proc/mounts output:\n${r.output}")
        _mountedImages.value = if (r.exitCode != 0 || r.output.isBlank()) {
            Log.d("MountManager", "No entries found in /proc/mounts matching $mountsDir/")
            emptyList()
        } else {
            val parsed = r.output.lineSequence().mapNotNull { line ->
                val p = line.trim().split(Regex("\\s+"))
                val device = p.getOrNull(0) ?: "null"
                val mountPoint = p.getOrNull(1) ?: "null"
                val fsTypeStr = p.getOrNull(2) ?: "null"
                val fs = p.getOrNull(2)?.let { FS_LIST[it] }
                when {
                    p.size < 3 -> {
                        Log.d("MountManager", "  SKIP: $line | Reason: not enough fields (${p.size} < 3)")
                        null
                    }
                    fs == null -> {
                        Log.d("MountManager", "  SKIP: $line | Reason: unknown fs type '$fsTypeStr'")
                        null
                    }
                    "loop" !in device -> {
                        Log.d("MountManager", "  SKIP: $line | Reason: not a loop device (device='$device')")
                        null
                    }
                    else -> {
                        Log.d("MountManager", "  PARSE: $line | device=$device, mp=$mountPoint, fs=$fsTypeStr")
                        MountedImage(device, mountPoint, device, fs)
                    }
                }
            }.distinctBy { "${it.mountPoint}|${it.loopDevice}|${it.devicePath}" }
                .sortedBy { it.mountPoint }.toList()
            Log.d("MountManager", "Final mounted images count: ${parsed.size}")
            parsed.forEach { img ->
                Log.d("MountManager", "  Mounted: ${img.mountPoint} (${img.fsType.mountType})")
            }
            parsed
        }
    }

    fun mountImage(
        path: String, mode: MountMode = MountMode.LOCAL, mountDirName: String? = null
    ): OpResult {
        if (!_envStatus.value.ready) return OpResult.failure(Exception(ctx.getString(R.string.error_env_not_ready)))
        refreshMountedImages()
        if (_mountedImages.value.size >= maxMounts) return OpResult.failure(
            Exception(
                ctx.getString(
                    R.string.error_mount_limit_reached, maxMounts
                )
            )
        )

        val imagePath = path.trim()
        if (imagePath.isBlank()) return OpResult.failure(Exception(ctx.getString(R.string.error_empty_path)))
        val isIso = imagePath.endsWith(".iso", ignoreCase = true)
        if (!imagePath.endsWith(".img", ignoreCase = true) && !isIso) return OpResult.failure(
            Exception(ctx.getString(R.string.error_only_img_iso_supported))
        )
        val imageFile = File(imagePath)
        if (!imageFile.exists()) return OpResult.failure(
            Exception(
                ctx.getString(
                    R.string.error_image_not_found, imagePath
                )
            )
        )
        if (!validatePath(imagePath)) return OpResult.failure(Exception(ctx.getString(R.string.error_image_path_invalid_chars)))

        val imgArg = pathArg(imagePath)

        // reject sparse images, not attempted for now (skip for ISOs)
        if (!isIso && RootShell.cmd(
                "hexdump",
                ShellArg.literal("-C"),
                ShellArg.literal("-n"),
                ShellArg.literal("20000"),
                imgArg,
                busyboxBin = busyboxBin,
                pipeInto = ShellCmd.of("grep", ShellArg.of("3a ff 26 ed"))
            ).let { it.exitCode == 0 && it.output.isNotBlank() }
        ) return OpResult.failure(Exception(ctx.getString(R.string.error_sparse_not_supported)))

        val fsType: FsType
        try {
            fsType = detectFilesystem(ctx, imagePath, busyboxBin) ?: run {
                Log.w(
                    "MountManager",
                    "fs detection failed for $imagePath (${imageFile.length()} bytes)"
                )
                return OpResult.failure(Exception(ctx.getString(R.string.error_unsupported_filesystem)))
            }
        } catch (e: PartitionedImageException) {
            Log.d("MountManager", "Partitioned image detected for $imagePath")
            val withFs =
                probePartitionFilesystems(ctx, imagePath, e.tableInfo.partitions, busyboxBin)
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
        if (!_envStatus.value.ready) return OpResult.failure(Exception(ctx.getString(R.string.error_env_not_ready)))
        refreshMountedImages()
        if (_mountedImages.value.size >= maxMounts) return OpResult.failure(
            Exception(
                ctx.getString(
                    R.string.error_mount_limit_reached, maxMounts
                )
            )
        )
        val imagePath = path.trim()
        val imageFile = File(imagePath)
        val fsType = partition.detectedFs ?: return OpResult.failure(
            Exception(
                ctx.getString(
                    R.string.error_no_fs_on_partition, partition.index
                )
            )
        )
        val stem = mountDirName
            ?: filenameToMountStem(imageFile.nameWithoutExtension + "_p${partition.index}")
        return performMount(
            imagePath, stem, fsType, mode, partition.offsetBytes, partition.sizeBytes
        )
    }

    // holds the partition table from the last PartitionedImageException
    private var _pendingPartitionResult: PartitionedImageResult? = null
    val pendingPartitionResult: PartitionedImageResult? get() = _pendingPartitionResult

    // probe partition table and detect filesystems for each partition (for re-mounting with stored selection)
    fun probePartitions(path: String): PartitionedImageResult? {
        val table = probePartitionTable(ctx, path, busyboxBin) ?: return null
        val withFs = probePartitionFilesystems(ctx, path, table.partitions, busyboxBin)
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
        if (RootShell.cmd(
                "losetup",
                ShellArg.literal("-a"),
                busyboxBin = busyboxBin,
                suppressErr = true,
                pipeInto = ShellCmd.of("grep", ShellArg.literal("-F"), imgArg)
            ).let { it.exitCode == 0 && it.output.isNotBlank() }
        ) {
            refreshMountedImages()
            return OpResult.failure(Exception(ctx.getString(R.string.error_image_already_mounted)))
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
        // instead of parsing its own output, check /proc/mounts directly for the mount point
        val isMounted = RootShell.cmd(
            "grep",
            ShellArg.literal("-qF"),
            ShellArg.of(" $mp "),
            pathArg("/proc/mounts"),
            busyboxBin = busyboxBin
        )
        if (isMounted.exitCode == 0) {
            refreshMountedImages()
            return OpResult.success("Already mounted at $mp")
        }
        val result = doMount(ctx, imagePath, mp, fsType, opts, busyboxBin, partOffset, partSize)
        if (result.isSuccess) {
            if (fsType.posixPermissions) makeAccessible(mp, mountsDir, busyboxBin)
            refreshMountedImages()
            ImageProvider.notifyRootsChanged(ctx)
        }
        return result
    }

    fun unmountImage(item: MountedImage): OpResult {
        if (!item.mountPoint.startsWith("$mountsDir/")) return OpResult.failure(
            Exception(
                ctx.getString(
                    R.string.error_invalid_mount_point
                )
            )
        )
        if (!validatePath(item.mountPoint) || !validatePath(item.loopDevice)) return OpResult.failure(
            Exception(ctx.getString(R.string.error_path_invalid_chars))
        )
        val mpArg = pathArg(item.mountPoint)
        val loopArg = loopDevArg(item.loopDevice)
        if (item.fsType.posixPermissions) restorePermissions(item.mountPoint, busyboxBin)
        RootShell.cmd(
            "fuser",
            ShellArg.literal("-km"),
            mpArg,
            busyboxBin = busyboxBin,
            suppressErr = true,
            ignoreError = true
        )
        // try busybox umount first, then system umount
        val umountResult = RootShell.cmd("umount", mpArg, busyboxBin = busyboxBin)
        if (umountResult.exitCode != 0) {
            val fallback = RootShell.cmd("umount", mpArg)
            if (fallback.exitCode != 0) return OpResult.failure(Exception(ctx.getString(R.string.error_unmount_failed)))
        }
        RootShell.cmd(
            "losetup",
            ShellArg.literal("-d"),
            loopArg,
            busyboxBin = busyboxBin,
            suppressErr = true,
            ignoreError = true
        )
        RootShell.cmd("rmdir", mpArg, suppressErr = true, ignoreError = true)
        refreshMountedImages()
        ImageProvider.notifyRootsChanged(ctx)
        return OpResult.success("Unmounted ${item.mountPoint}")
    }

    fun formatImage(path: String, fsType: String = "ext4"): OpResult =
        formatImage(ctx, path, _envStatus.value.ready, busyboxBin, fsType)

    fun createStorageBind(
        stem: String, bindDir: String? = null, directMount: Boolean = false
    ): OpResult {
        if (!_envStatus.value.ready) return OpResult.failure(Exception(ctx.getString(R.string.error_env_not_ready)))
        val targetBindDir = bindDir
            ?: return OpResult.failure(Exception(ctx.getString(R.string.error_bind_dir_not_specified)))
        validateBindDir(ctx, targetBindDir)?.let { return OpResult.failure(Exception(it)) }
        val resolvedBindDir = try {
            File(targetBindDir).canonicalPath
        } catch (e: Exception) {
            return OpResult.failure(
                Exception(
                    ctx.getString(
                        R.string.error_resolve_bind_dir, e.message ?: ""
                    )
                )
            )
        }
        validateBindDir(ctx, resolvedBindDir)?.let {
            return OpResult.failure(Exception(ctx.getString(R.string.error_bind_dir_rejected, it)))
        }
        val verifiedBindDir = try {
            File(targetBindDir).canonicalPath
        } catch (_: Exception) {
            return OpResult.failure(Exception(ctx.getString(R.string.error_path_changed_validation)))
        }
        if (verifiedBindDir != resolvedBindDir) {
            return OpResult.failure(Exception(ctx.getString(R.string.error_bind_path_changed)))
        }
        val safeStem = sanitizeStem(stem)
        val source = "$mountsDir/$safeStem"
        val target = if (directMount) resolvedBindDir else "$resolvedBindDir/$safeStem"
        if (!validatePath(source) || !validatePath(target) || !validatePath(resolvedBindDir)) return OpResult.failure(
            Exception(ctx.getString(R.string.error_path_invalid_chars))
        )
        val srcArg = pathArg(source)
        val tgtArg = pathArg(target)
        val dirArg = pathArg(resolvedBindDir)
        // check if the parent directory exists with media_rw ownership so it's visible via FUSE
        val mkdirDir = RootShell.cmd(
            "mkdir", ShellArg.literal("-p"), dirArg, chain = ShellCmd.chain(
                ShellCmd.of("chown", ShellArg.literal("1023:1023"), dirArg),
                ShellCmd.of("chmod", enumArg("775", VALID_CHMOD_MODES), dirArg)
            )
        )
        if (mkdirDir.exitCode != 0) return OpResult.failure(
            Exception(
                ctx.getString(
                    R.string.error_failed_create_storage_dir, resolvedBindDir
                )
            )
        )
        // check if already bind-mounted
        if (RootShell.cmd(
                "grep", ShellArg.literal("-qF"), ShellArg.of(" $target "), pathArg("/proc/mounts")
            ).exitCode == 0
        ) return OpResult.success("Already exposed at $target")
        if (directMount) {
            val lsResult = RootShell.cmd(
                "ls", ShellArg.literal("-A"), tgtArg, suppressErr = true
            )
            if (lsResult.exitCode == 0 && lsResult.output.isNotBlank()) {
                return OpResult.failure(
                    Exception(
                        ctx.getString(
                            R.string.dialog_bind_nonempty, target
                        )
                    )
                )
            }
        }
        // create mount point directory
        val mkdirTgt = RootShell.cmd(
            "mkdir", ShellArg.literal("-p"), tgtArg, chain = ShellCmd.chain(
                ShellCmd.of("chown", ShellArg.literal("1023:1023"), tgtArg),
                ShellCmd.of("chmod", enumArg("775", VALID_CHMOD_MODES), tgtArg)
            )
        )
        if (mkdirTgt.exitCode != 0) return OpResult.failure(
            Exception(
                ctx.getString(
                    R.string.error_failed_create_mount_point_dir, target
                )
            )
        )
        // bind mount
        val r =
            RootShell.cmd("mount", ShellArg.literal("--bind"), srcArg, tgtArg, redirectErr = true)
        if (r.exitCode != 0) return OpResult.failure(
            Exception(
                ctx.getString(
                    R.string.error_bind_mount_failed, r.output
                )
            )
        )
        // set SELinux context so media layer can traverse it
        RootShell.cmd(
            "chcon",
            ShellArg.literal("-R"),
            secontextArg("u:object_r:media_rw_data_file:s0"),
            tgtArg,
            suppressErr = true,
            ignoreError = true
        )
        return OpResult.success("Exposed at $target")
    }

    // unmount a bind mount at [bindDir]/[stem] (or [bindDir] directly if directMount) and remove the empty directory
    fun removeStorageBind(
        stem: String, bindDir: String? = null, directMount: Boolean = false
    ): OpResult {
        val targetBindDir =
            bindDir ?: return OpResult.success("Bind directory not specified, nothing to remove")
        validateBindDir(ctx, targetBindDir)?.let { return OpResult.failure(Exception(it)) }
        val resolvedBindDir = try {
            File(targetBindDir).canonicalPath
        } catch (e: Exception) {
            return OpResult.failure(
                Exception(
                    ctx.getString(
                        R.string.error_resolve_bind_dir, e.message ?: ""
                    )
                )
            )
        }
        validateBindDir(ctx, resolvedBindDir)?.let {
            return OpResult.failure(Exception(ctx.getString(R.string.error_bind_dir_rejected, it)))
        }
        // re-resolve to detect path manipulation between validation and use
        val verifiedBindDir = try {
            File(targetBindDir).canonicalPath
        } catch (_: Exception) {
            return OpResult.failure(Exception(ctx.getString(R.string.error_path_changed_validation)))
        }
        if (verifiedBindDir != resolvedBindDir) {
            return OpResult.failure(Exception(ctx.getString(R.string.error_bind_path_changed)))
        }
        val safeStem = sanitizeStem(stem)
        val target = if (directMount) resolvedBindDir else "$resolvedBindDir/$safeStem"
        if (!validatePath(target)) return OpResult.failure(Exception(ctx.getString(R.string.error_path_invalid_chars)))
        val tgtArg = pathArg(target)
        // unmount if mounted
        val isMounted = RootShell.cmd(
            "grep", ShellArg.literal("-qF"), ShellArg.of(" $target "), pathArg("/proc/mounts")
        )
        if (isMounted.exitCode == 0) {
            RootShell.cmd("umount", tgtArg, suppressErr = true, ignoreError = true)
        }
        // clean up empty directory
        // for subdirectory mounts: remove the stem subdirectory
        // for direct mounts: remove the folder only if it's now empty (safe post-unmount)
        RootShell.cmd("rmdir", tgtArg, suppressErr = true, ignoreError = true)
        return OpResult.success("Storage mount removed: $target")
    }
}
