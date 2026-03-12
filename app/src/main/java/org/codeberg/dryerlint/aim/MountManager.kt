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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.codeberg.dryerlint.aim.utils.FS_LIST
import org.codeberg.dryerlint.aim.utils.PartitionEntry
import org.codeberg.dryerlint.aim.utils.PartitionTableInfo
import org.codeberg.dryerlint.aim.utils.PartitionedImageException
import org.codeberg.dryerlint.aim.utils.RootShell
import org.codeberg.dryerlint.aim.utils.ShellArg
import org.codeberg.dryerlint.aim.utils.ShellCmd
import org.codeberg.dryerlint.aim.utils.ALLOWED_CHMOD_MODES
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
    val mountType: String,
    val posixPermissions: Boolean,
    val readOnly: Boolean = false,
) {
    EXT4("ext4", true),
    VFAT("vfat", false),
    EXFAT("exfat", false),
    ISO9660("iso9660", false, readOnly = true),
}

data class MountedImage(
    val loopDevice: String,
    val mountPoint: String,
    val fsType: FsType,
)

data class PartitionedImageResult(
    val tableInfo: PartitionTableInfo,
    val partitions: List<PartitionEntry>,
)

data class EnvironmentStatus(
    val rootAvailable: Boolean = false,
    val rootMessage: String = "",
    val busyboxAvailable: Boolean = false,
    val busyboxPath: String = "",
    val busyboxMessage: String = "",
    val ready: Boolean = false,
)

sealed class MountResult {
    data class Mounted(val mountPoint: String) : MountResult()
    data class AlreadyMounted(val mountPoint: String) : MountResult()
    data class Unmounted(val mountPoint: String) : MountResult()
    data class PartitionedImage(val result: PartitionedImageResult) : MountResult()
    data class Failure(val message: String) : MountResult()
}

sealed class BindResult {
    data class Exposed(val target: String) : BindResult()
    data class AlreadyExposed(val target: String) : BindResult()
    data class Removed(val target: String) : BindResult()
    data class Failure(val message: String) : BindResult()
    object Skipped : BindResult()
}

fun generateMountStem(
    imagePath: String,
    allImagePaths: List<String>,
    diskLabel: String? = null,
    allLabels: Map<String, String?> = emptyMap(),
): String {
    fun sanitize(path: String) = filenameToMountStem(File(path).nameWithoutExtension)

    if (diskLabel != null && isValidLabelStem(diskLabel)) {
        val labelConflict = allLabels.any { (path, label) ->
            path != imagePath && label != null && isValidLabelStem(label) && label == diskLabel
        }
        if (!labelConflict) return diskLabel
    }

    val sanitized = sanitize(imagePath)
    if (allImagePaths.none { it != imagePath && sanitize(it) == sanitized }) return sanitized

    val h = File(imagePath).nameWithoutExtension.hashCode().toUInt()
    return String.format("%04X-%04X", (h.toInt() shr 16) and 0xFFFF, h.toInt() and 0xFFFF)
}

class MountManager(
    appContext: Context,
    private val rootsChangedNotifier: RootsChangedNotifier,
) {
    interface RootsChangedNotifier {
        fun notify(context: Context)
    }

    private val ctx = appContext.applicationContext
    val mountsDir: String = File(ctx.filesDir, "mounts").apply { mkdirs() }.absolutePath

    private val mountMutex = Mutex()
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

    private fun fail(resId: Int, vararg args: Any) =
        MountResult.Failure(ctx.getString(resId, *args))

    private fun bindFail(resId: Int, vararg args: Any) =
        BindResult.Failure(ctx.getString(resId, *args))

    private fun requireEnvReady(): MountResult.Failure? =
        if (!_envStatus.value.ready) fail(R.string.error_env_not_ready) else null

    private fun requireMountCapacity(): MountResult.Failure? =
        if (_mountedImages.value.size >= maxMounts) fail(
            R.string.error_mount_limit_reached, maxMounts
        ) else null

    private fun isMountedAt(mountPoint: String): Boolean = RootShell.cmd(
        "grep",
        ShellArg.literal("-qF"),
        ShellArg.of(" $mountPoint "),
        pathArg("/proc/mounts"),
        busyboxBin = busyboxBin
    ).exitCode == 0

    private fun detachLoop(loopDevice: String) {
        RootShell.cmd(
            "losetup",
            ShellArg.literal("-d"),
            loopDevArg(loopDevice),
            busyboxBin = busyboxBin,
            suppressErr = true,
            ignoreError = true
        )
    }

    private fun attachedLoops(imagePath: String): List<String> {
        val r = RootShell.cmd(
            "losetup",
            ShellArg.literal("-a"),
            busyboxBin = busyboxBin,
            suppressErr = true,
            pipeInto = ShellCmd.of("grep", ShellArg.literal("-F"), pathArg(imagePath))
        )
        if (r.exitCode != 0 || r.output.isBlank()) return emptyList()
        return r.output.lineSequence().mapNotNull { line ->
            line.substringBefore(':').trim()
                .takeIf { it.matches(Regex("^/dev/(block/)?loop\\d+$")) }
        }.distinct().toList()
    }

    private fun detachStaleLoops(imagePath: String) {
        attachedLoops(imagePath).forEach { loop ->
            val mounted = RootShell.cmd(
                "grep",
                ShellArg.literal("-qF"),
                ShellArg.of("$loop "),
                pathArg("/proc/mounts"),
                busyboxBin = busyboxBin
            ).exitCode == 0
            if (!mounted) {
                L.w(TAG, "Detaching stale loop $loop for $imagePath")
                detachLoop(loop)
            }
        }
    }

    suspend fun checkEnvironment(): EnvironmentStatus = mountMutex.withLock {
        val (status, bb) = checkEnv(ctx)
        busyboxBin = bb
        _envStatus.value = status
        return status
    }

    val busyboxPath: String
        get() = busyboxBin

    private fun readMountedImages(): List<MountedImage> {
        val r = RootShell.cmd(
            "grep",
            ShellArg.literal("-F"),
            ShellArg.of("$mountsDir/"),
            pathArg("/proc/mounts"),
            ignoreError = true
        )
        L.d(TAG, "readMountedImages: exit=${r.exitCode}, blank=${r.output.isBlank()}")

        if (r.exitCode != 0 || r.output.isBlank()) {
            L.d(TAG, "No mounts found under $mountsDir/")
            return emptyList()
        }

        return r.output.lineSequence().mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"))
            val device = p.getOrNull(0)
            val mountPoint = p.getOrNull(1)
            val fsTypeStr = p.getOrNull(2)
            val fs = fsTypeStr?.let { FS_LIST[it] }
            when {
                p.size < 3 ->
                    L.d(TAG, "SKIP (fields=${p.size}): $line").let { null }
                fs == null ->
                    L.d(TAG, "SKIP (unknown fs '$fsTypeStr'): $line").let { null }
                device == null || "loop" !in device ->
                    L.d(TAG, "SKIP (not loop, device=$device): $line").let { null }
                else ->
                    MountedImage(device, mountPoint!!, fs)
                        .also { L.d(TAG, "MOUNT: $mountPoint ($fsTypeStr)") }
            }
        }.distinctBy { "${it.mountPoint}|${it.loopDevice}" }
            .sortedBy { it.mountPoint }
            .toList()
            .also { L.d(TAG, "Total mounted: ${it.size}") }
    }

    fun refreshMountedImages() {
        _mountedImages.value = readMountedImages()
    }

    suspend fun mountImage(
        path: String,
        mode: MountMode = MountMode.LOCAL,
        mountDirName: String? = null,
    ): MountResult = mountMutex.withLock {
        requireEnvReady()?.let { return it }
        refreshMountedImages()
        requireMountCapacity()?.let { return it }

        val imagePath = path.trim()
        if (imagePath.isBlank()) return fail(R.string.error_empty_path)

        val isIso = imagePath.endsWith(".iso", ignoreCase = true)
        if (!imagePath.endsWith(".img", ignoreCase = true) && !isIso)
            return fail(R.string.error_only_img_iso_supported)

        val imageFile = File(imagePath)
        if (!imageFile.exists()) return fail(R.string.error_image_not_found, imagePath)
        if (!validatePath(imagePath)) return fail(R.string.error_image_path_invalid_chars)

        // Sparse ext4 images (identified by magic bytes at offset ~0x1000) cannot be
        // loop-mounted without first being converted to raw. We reject them early rather
        // than letting the kernel mount fail with a cryptic error.
        if (!isIso && isSparseImage(imagePath)) return fail(R.string.error_sparse_not_supported)

        val fsType = try {
                when (val detect = detectFilesystem(ctx, imagePath, busyboxBin)) {
                    is org.codeberg.dryerlint.aim.utils.DetectFsResult.Found -> detect.fs
                    is org.codeberg.dryerlint.aim.utils.DetectFsResult.Unknown -> {
                        L.w(TAG, "fs detection failed for $imagePath (${imageFile.length()} bytes)")
                        return fail(R.string.error_unsupported_filesystem)
                    }
                    is org.codeberg.dryerlint.aim.utils.DetectFsResult.AccessError -> {
                        L.w(TAG, "fs access error for $imagePath: ${detect.reason}")
                        return fail(R.string.error_ksu_or_alike_permission)
                    }
                }
        } catch (e: PartitionedImageException) {
            L.d(TAG, "Partitioned image detected: $imagePath")
            val partResult = PartitionedImageResult(
                e.tableInfo,
                probePartitionFilesystems(ctx, imagePath, e.tableInfo.partitions, busyboxBin)
            )
            return MountResult.PartitionedImage(partResult)
        }

        L.d(TAG, "Detected fs=${fsType.mountType} for $imagePath")

        val loops = attachedLoops(imagePath)
        if (loops.isNotEmpty()) {
            val mountedLoop = loops.firstOrNull { loop ->
                RootShell.cmd(
                    "grep",
                    ShellArg.literal("-qF"),
                    ShellArg.of("$loop "),
                    pathArg("/proc/mounts"),
                    busyboxBin = busyboxBin
                ).exitCode == 0
            }
            if (mountedLoop != null) {
                L.w(TAG, "Image already mounted via $mountedLoop")
                refreshMountedImages()
                return fail(R.string.error_image_already_mounted)
            }
            loops.forEach { L.w(TAG, "Stale loop $it — detaching"); detachLoop(it) }
        }

        val stem = sanitizeStem(
            mountDirName ?: filenameToMountStem(imageFile.nameWithoutExtension)
        )
        return performMount(imagePath, stem, fsType, mode)
    }

    suspend fun mountPartition(
        path: String,
        partition: PartitionEntry,
        mode: MountMode = MountMode.LOCAL,
        mountDirName: String? = null,
    ): MountResult = mountMutex.withLock {
        requireEnvReady()?.let { return it }
        refreshMountedImages()
        requireMountCapacity()?.let { return it }

        val imagePath = path.trim()
        if (!validatePath(imagePath)) return fail(R.string.error_image_path_invalid_chars)

        val fsType = partition.detectedFs
            ?: return fail(R.string.error_no_fs_on_partition, partition.index)
        val stem = sanitizeStem(
            mountDirName
                ?: filenameToMountStem(File(imagePath).nameWithoutExtension + "_p${partition.index}")
        )

        return performMount(imagePath, stem, fsType, mode, partition.offsetBytes, partition.sizeBytes)
    }

    fun probePartitions(path: String): PartitionedImageResult? {
        val table = probePartitionTable(ctx, path, busyboxBin) ?: return null
        return PartitionedImageResult(
            table,
            probePartitionFilesystems(ctx, path, table.partitions, busyboxBin)
        )
    }

    suspend fun unmountImage(item: MountedImage): MountResult = mountMutex.withLock {
        if (!item.mountPoint.startsWith("$mountsDir/"))
            return fail(R.string.error_invalid_mount_point)
        if (!validatePath(item.mountPoint) || !validatePath(item.loopDevice))
            return fail(R.string.error_path_invalid_chars)

        val mpArg = pathArg(item.mountPoint)

        if (item.fsType.posixPermissions) {
            restorePermissions(item.mountPoint, busyboxBin).onFailure {
                L.w(TAG, "restorePermissions failed for ${item.mountPoint}: ${it.message}")
            }
        }

        RootShell.cmd(
            "fuser", ShellArg.literal("-km"), mpArg,
            busyboxBin = busyboxBin, suppressErr = true, ignoreError = true
        )

        val umountOk = RootShell.cmd("umount", mpArg, busyboxBin = busyboxBin).exitCode == 0
                || RootShell.cmd("umount", mpArg).exitCode == 0
        if (!umountOk) {
            L.e(TAG, "Failed to unmount ${item.mountPoint}")
            return fail(R.string.error_unmount_failed)
        }

        val loopDetached = runCatching { detachLoop(item.loopDevice) }
        if (loopDetached.isFailure) {
            L.w(TAG, "Loop detach failed for ${item.loopDevice}: ${loopDetached.exceptionOrNull()?.message}")
        }

        RootShell.cmd("rmdir", mpArg, suppressErr = true, ignoreError = true)
        refreshMountedImages()
        rootsChangedNotifier.notify(ctx)
        return MountResult.Unmounted(item.mountPoint)
    }

    fun formatImage(path: String, fsType: String = "ext4"): OpResult =
        formatImage(ctx, path, _envStatus.value.ready, busyboxBin, fsType)

    suspend fun createStorageBind(
        stem: String,
        bindDir: String? = null,
        directMount: Boolean = false,
    ): BindResult = mountMutex.withLock {
        requireEnvReadyBind()?.let { return it }
        bindDir ?: return bindFail(R.string.error_bind_dir_not_specified)

        val rbd = resolveAndValidateBindDir(bindDir)
            ?: return bindFail(R.string.error_bind_dir_rejected)

        val safeStem = sanitizeStem(stem)
        val source = "$mountsDir/$safeStem"
        val target = if (directMount) rbd else "$rbd/$safeStem"

        if (!validatePath(source) || !validatePath(target) || !validatePath(rbd))
            return bindFail(R.string.error_path_invalid_chars)

        val tgtArg = pathArg(target)
        val dirArg = pathArg(rbd)

        val mkdirDir = RootShell.cmd(
            "mkdir", ShellArg.literal("-p"), dirArg,
            chain = ShellCmd.chain(
                ShellCmd.of("chown", ShellArg.literal("1023:1023"), dirArg),
                ShellCmd.of("chmod", enumArg("775", ALLOWED_CHMOD_MODES), dirArg)
            )
        )
        if (mkdirDir.exitCode != 0) {
            L.e(TAG, "Failed to create storage dir: $rbd")
            return bindFail(R.string.error_failed_create_storage_dir, rbd)
        }

        if (isMountedAt(target)) return BindResult.AlreadyExposed(target)

        if (directMount) {
            val ls = RootShell.cmd("ls", ShellArg.literal("-A"), tgtArg, suppressErr = true)
            if (ls.exitCode == 0 && ls.output.isNotBlank())
                return bindFail(R.string.dialog_bind_nonempty, target)
        }

        val mkdirTgt = RootShell.cmd(
            "mkdir", ShellArg.literal("-p"), tgtArg,
            chain = ShellCmd.chain(
                ShellCmd.of("chown", ShellArg.literal("1023:1023"), tgtArg),
                ShellCmd.of("chmod", enumArg("775", ALLOWED_CHMOD_MODES), tgtArg)
            )
        )
        if (mkdirTgt.exitCode != 0) {
            L.e(TAG, "Failed to create mount point dir: $target")
            return bindFail(R.string.error_failed_create_mount_point_dir, target)
        }

        val r = RootShell.cmd(
            "mount", ShellArg.literal("--bind"), pathArg(source), tgtArg, redirectErr = true
        )
        if (r.exitCode != 0) {
            L.e(TAG, "Bind mount failed ($target): ${r.output}")
            return bindFail(R.string.error_bind_mount_failed, r.output)
        }

        RootShell.cmd(
            "chcon", ShellArg.literal("-R"),
            secontextArg("u:object_r:media_rw_data_file:s0"), tgtArg,
            suppressErr = true, ignoreError = true
        )
        return BindResult.Exposed(target)
    }

    suspend fun removeStorageBind(
        stem: String,
        bindDir: String? = null,
        directMount: Boolean = false,
    ): BindResult = mountMutex.withLock {
        bindDir ?: return BindResult.Skipped

        val rbd = resolveAndValidateBindDir(bindDir)
            ?: return bindFail(R.string.error_bind_dir_rejected)

        val target = if (directMount) rbd else "$rbd/${sanitizeStem(stem)}"
        if (!validatePath(target)) return bindFail(R.string.error_path_invalid_chars)

        val tgtArg = pathArg(target)
        if (isMountedAt(target)) {
            RootShell.cmd("umount", tgtArg, suppressErr = true, ignoreError = true)
        }
        RootShell.cmd("rmdir", tgtArg, suppressErr = true, ignoreError = true)
        return BindResult.Removed(target)
    }

    private fun requireEnvReadyBind(): BindResult.Failure? =
        if (!_envStatus.value.ready) BindResult.Failure(ctx.getString(R.string.error_env_not_ready)) else null

    /**
     * detects sparse ext4 images via the ext4 superblock magic (0xEF53) at byte offset 0x438, reads only 2KB
     */
    private fun isSparseImage(imagePath: String): Boolean {
        // Detect Android sparse image magic 0xED26FF3A (hexdump prints little-endian bytes as "3a ff 26 ed").
        // Read only the first 2KB since the magic is within that range.
        return RootShell.cmd(
            "hexdump",
            ShellArg.literal("-C"),
            ShellArg.literal("-n"),
            ShellArg.literal("2048"),
            pathArg(imagePath),
            busyboxBin = busyboxBin,
            pipeInto = ShellCmd.of("grep", ShellArg.of("3a ff 26 ed"))
        ).let { it.exitCode == 0 && it.output.isNotBlank() }
    }

    /**
     * canonicalizes and double-checks [bindDir] against [validateBindDir] to detect symlink
     * races between the two resolutions. Returns null if the path is rejected at any stage.
     */
    private fun resolveAndValidateBindDir(bindDir: String): String? {
        if (validateBindDir(ctx, bindDir) != null) {
            L.w(TAG, "bindDir rejected before canonicalization: $bindDir")
            return null
        }
        val resolved = try {
            File(bindDir).canonicalPath
        } catch (e: Exception) {
            L.e(TAG, "canonicalPath failed for $bindDir: ${e.message}")
            return null
        }
        if (validateBindDir(ctx, resolved) != null) {
            L.w(TAG, "bindDir rejected after canonicalization: $resolved")
            return null
        }
        val verified = try {
            File(resolved).canonicalPath
        } catch (e: Exception) {
            L.e(TAG, "re-resolve failed for $resolved: ${e.message}")
            return null
        }
        if (verified != resolved) {
            L.e(TAG, "bindDir changed between resolutions — possible symlink race: $bindDir")
            return null
        }
        return resolved
    }

    private fun performMount(
        imagePath: String,
        stem: String,
        fsType: FsType,
        mode: MountMode,
        partOffset: Long = 0,
        partSize: Long = 0,
    ): MountResult {
        val mp = "$mountsDir/$stem"
        if (isMountedAt(mp)) {
            refreshMountedImages()
            return MountResult.AlreadyMounted(mp)
        }

        val opts = buildMountOpts(fsType, mode)
        val result = doMount(ctx, imagePath, mp, fsType, opts, busyboxBin, partOffset, partSize)

        if (result.isSuccess) {
            if (!isMountedAt(mp)) {
                L.e(TAG, "Mount reported success but $mp absent from /proc/mounts")
                detachStaleLoops(imagePath)
                return fail(R.string.error_mount_not_visible)
            }
            if (fsType.posixPermissions) {
                makeAccessible(mp, mountsDir, busyboxBin).onFailure {
                    L.w(TAG, "makeAccessible failed for $mp: ${it.message}")
                }
            }
            refreshMountedImages()
            rootsChangedNotifier.notify(ctx)
            return MountResult.Mounted(mp)
        }

        L.e(TAG, "doMount failed for $imagePath -> $mp: ${result.exceptionOrNull()?.message}")
        val cause = result.exceptionOrNull()?.message ?: ""
        return MountResult.Failure(ctx.getString(R.string.error_mount_failed_output, if (cause.isNotBlank()) cause else "unknown"))
    }

    companion object {
        private const val TAG = "MountManager"
    }
}
