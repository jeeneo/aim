// SPDX-License-Identifier: GPL-3.0-or-later

package org.codeberg.aimapp.utils.mounts

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.codeberg.aimapp.R
import org.codeberg.aimapp.utils.disk.DetectFsResult
import org.codeberg.aimapp.utils.disk.FS_MAP
import org.codeberg.aimapp.utils.disk.PartitionedImageException
import org.codeberg.aimapp.utils.disk.detectFilesystem
import org.codeberg.aimapp.utils.envStatus
import org.codeberg.aimapp.utils.paths.filenameToMountStem
import org.codeberg.aimapp.utils.paths.isValidLabelStem
import org.codeberg.aimapp.utils.paths.sanitizeStem
import org.codeberg.aimapp.utils.paths.validateBindDir
import org.codeberg.aimapp.utils.paths.validatePath
import org.codeberg.aimapp.utils.shell.RootShell
import org.codeberg.aimapp.utils.shell.ShellArg
import org.codeberg.aimapp.utils.shell.ShellCmd
import org.codeberg.aimapp.utils.shell.enumArg
import org.codeberg.aimapp.utils.shell.loopDevArg
import org.codeberg.aimapp.utils.shell.pathArg
import org.codeberg.aimapp.utils.shell.secontextArg
import java.io.File
import java.io.RandomAccessFile

enum class MountMode { LOCAL, PUBLIC }

sealed class FsType(
    val mountType: String,
    val posixPermissions: Boolean,
    val readOnly: Boolean = false,
) {
    data object EXT4 : FsType("ext4", posixPermissions = true)
    data object VFAT : FsType("vfat", posixPermissions = false)
    data object EXFAT : FsType("exfat", posixPermissions = false)
    data object ISO9660 : FsType("iso9660", posixPermissions = false, readOnly = true)
    data class OTHER(val name: String) : FsType(name, posixPermissions = true)
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
    val storageAvailable: Boolean = false,
    val storageMessage: String = "",
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
    val conflicts = allImagePaths.filter { sanitize(it) == sanitized }
    if (conflicts.size <= 1) return sanitized
    if (conflicts.first() == imagePath) return sanitized
    val h = imagePath.hashCode().toUInt()
    return String.format("%04X-%04X", (h.toInt() shr 16) and 0xFFFF, h.toInt() and 0xFFFF)
}

class MountManager(
    appContext: Context,
    private val onRootsChanged: (Context) -> Unit,
) {
    companion object {
        private const val TAG = "MountManager"
        private const val REFRESH_COALESCE_MS = 300L
    }

    private val ctx = appContext.applicationContext
    val mountsDir: String = File(ctx.filesDir, "mounts").apply { mkdirs() }.absolutePath
    private val mountMutex = Mutex()
    private val refreshLock = Any()
    private var lastRefreshMs = 0L
    private val maxMounts = 10
    private val _mountedImages = MutableStateFlow<List<MountedImage>>(emptyList())
    val mountedImages: StateFlow<List<MountedImage>> = _mountedImages.asStateFlow()

    private val busyboxBin: String get() = envStatus.value.busyboxPath

    private fun fail(resId: Int, vararg args: Any) =
        MountResult.Failure(ctx.getString(resId, *args))

    private fun bindFail(resId: Int, vararg args: Any) =
        BindResult.Failure(ctx.getString(resId, *args))

    private fun requireEnvReady(): MountResult.Failure? =
        if (!envStatus.value.ready) fail(R.string.error_env_not_ready) else null

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
            ignoreError = true
        )
    }

    private fun attachedLoops(imagePath: String): List<String> {
        val r = RootShell.cmd(
            "losetup",
            ShellArg.literal("-a"),
            busyboxBin = busyboxBin,
            ignoreError = true,
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
                Log.w(TAG, "Detaching stale loop $loop for $imagePath")
                detachLoop(loop)
            }
        }
    }

    private fun readMountedImages(): List<MountedImage> {
        val r = RootShell.cmd(
            "grep",
            ShellArg.literal("-F"),
            ShellArg.of("$mountsDir/"),
            pathArg("/proc/mounts"),
            ignoreError = true
        )
        Log.d(TAG, "readMountedImages: exit=${r.exitCode}, blank=${r.output.isBlank()}")
        if (r.exitCode != 0 || r.output.isBlank()) {
            Log.d(TAG, "No mounts found under $mountsDir/")
            return emptyList()
        }
        return r.output.lineSequence().mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"))
            val device = p.getOrNull(0)
            val mountPoint = p.getOrNull(1)
            val fsTypeStr = p.getOrNull(2)
            val fs = fsTypeStr?.let { FS_MAP[it] ?: FsType.OTHER(it) }
            when {
                p.size < 3 -> Log.d(TAG, "SKIP (fields=${p.size}): $line").let { null }
                fs == null -> Log.d(TAG, "SKIP (unknown fs '${null}'): $line").let { null }
                device == null || "loop" !in device -> Log.d(
                    TAG, "SKIP (not loop, device=$device): $line"
                ).let { null }

                else -> MountedImage(device, mountPoint!!, fs).also {
                    Log.d(
                        TAG, "MOUNT: $mountPoint ($fsTypeStr)"
                    )
                }
            }
        }.distinctBy { "${it.mountPoint}|${it.loopDevice}" }.sortedBy { it.mountPoint }.toList()
            .also { Log.d(TAG, "Total mounted: ${it.size}") }
    }

    fun refreshMountedImages(force: Boolean = false) {
        synchronized(refreshLock) {
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastRefreshMs < REFRESH_COALESCE_MS) {
                Log.d(TAG, "refreshMountedImages: coalesced")
                return
            }
            _mountedImages.value = readMountedImages()
            lastRefreshMs = SystemClock.elapsedRealtime()
        }
    }

    suspend fun mountImage(
        path: String,
        mode: MountMode = MountMode.LOCAL,
        mountDirName: String? = null,
        preservePermissions: Boolean = false,
    ): MountResult = mountMutex.withLock {
        requireEnvReady()?.let { return it }
        refreshMountedImages()
        requireMountCapacity()?.let { return it }

        val imagePath = path.trim()
        if (imagePath.isBlank()) return fail(R.string.error_empty_path)

        val isIso = imagePath.endsWith(".iso", ignoreCase = true)
        if (!imagePath.endsWith(
                ".img", ignoreCase = true
            ) && !isIso
        ) return fail(R.string.error_only_img_iso_supported)

        val imageFile = File(imagePath)
        if (!imageFile.exists()) return fail(R.string.error_image_not_found, imagePath)
        if (!validatePath(imagePath)) return fail(R.string.error_image_path_invalid_chars)

        // sparse ext4 images (identified by magic bytes at offset ~0x1000) cannot be
        // loop-mounted without first being converted to raw, reject them early rather
        // than letting the kernel mount fail with a cryptic error
        if (!isIso && isSparseImage(imagePath)) return fail(R.string.error_sparse_not_supported)

        val fsType = try {
            when (val detect = detectFilesystem(ctx, imagePath, busyboxBin)) {
                is DetectFsResult.Found -> detect.fs
                is DetectFsResult.Unknown -> {
                    Log.w(TAG, "fs detection failed for $imagePath (${imageFile.length()} bytes)")
                    return fail(R.string.error_unsupported_filesystem)
                }

                is DetectFsResult.AccessError -> {
                    Log.w(TAG, "fs access error for $imagePath: ${detect.reason}")
                    if ("EACCES" in detect.reason || "Permission denied" in detect.reason) {
                        return fail(R.string.error_storage_access_denied)
                    }
                    return fail(R.string.error_failed_to_access_image, detect.reason)
                }
            }
        } catch (e: PartitionedImageException) {
            Log.d(TAG, "Partitioned image detected: $imagePath")
            val partResult = PartitionedImageResult(
                e.tableInfo,
                probePartitionFilesystems(ctx, imagePath, e.tableInfo.partitions)
            )
            return MountResult.PartitionedImage(partResult)
        }

        Log.d(TAG, "Detected fs=${fsType.mountType} for $imagePath")

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
                Log.w(TAG, "Image already mounted via $mountedLoop")
                refreshMountedImages(force = true)
                return fail(R.string.error_image_already_mounted)
            }
            loops.forEach { Log.w(TAG, "Stale loop $it, detaching"); detachLoop(it) }
        }

        val stem = sanitizeStem(
            mountDirName ?: filenameToMountStem(imageFile.nameWithoutExtension)
        )
        return performMount(
            imagePath, stem, fsType, mode, preservePermissions = preservePermissions
        )
    }

    suspend fun mountPartition(
        path: String,
        partition: PartitionEntry,
        mode: MountMode = MountMode.LOCAL,
        mountDirName: String? = null,
        preservePermissions: Boolean = false,
    ): MountResult = mountMutex.withLock {
        requireEnvReady()?.let { return it }
        refreshMountedImages()
        requireMountCapacity()?.let { return it }

        val imagePath = path.trim()
        if (!validatePath(imagePath)) return fail(R.string.error_image_path_invalid_chars)

        val fsType =
            partition.detectedFs ?: return fail(R.string.error_no_fs_on_partition, partition.index)
        val stem = sanitizeStem(
            mountDirName
                ?: filenameToMountStem(File(imagePath).nameWithoutExtension + "_p${partition.index}")
        )

        return performMount(
            imagePath, stem, fsType, mode, partition.offsetBytes, preservePermissions
        )
    }

    fun probePartitions(path: String): PartitionedImageResult? {
        val table = probePartitionTable(ctx, path) ?: return null
        return PartitionedImageResult(
            table, probePartitionFilesystems(ctx, path, table.partitions)
        )
    }

    suspend fun unmountImage(
        item: MountedImage,
        preservePermissions: Boolean = false,
    ): MountResult = mountMutex.withLock {
        if (!item.mountPoint.startsWith("$mountsDir/")) return fail(R.string.error_invalid_mount_point)
        if (!validatePath(item.mountPoint) || !validatePath(item.loopDevice)) return fail(R.string.error_path_invalid_chars)
        val snapshotFile = "$mountsDir/.${item.mountPoint.removePrefix("$mountsDir/")}.perm"
        val mpArg = pathArg(item.mountPoint)
        Log.d(TAG, "Unmounting ${item.mountPoint} (loop=${item.loopDevice})")
        RootShell.cmd(
            "fuser", ShellArg.literal("-km"), mpArg, busyboxBin = busyboxBin, ignoreError = true
        )
        if (item.fsType.posixPermissions) {
            restorePermissions(
                item.mountPoint, snapshotFile, preservePermissions, busyboxBin
            ).onFailure {
                Log.w(
                    TAG,
                    "restorePermissionsFromSnapshot failed for ${item.mountPoint}: ${it.message}"
                )
            }
        } else if (!item.fsType.posixPermissions) {
            Log.w(TAG, "Skipping permission restore for non-POSIX fs ${item.fsType.mountType}")
        }

        val umountOk = RootShell.cmd(
            "umount", mpArg, busyboxBin = busyboxBin
        ).exitCode == 0 || RootShell.cmd("umount", mpArg).exitCode == 0
        if (!umountOk) {
            Log.e(TAG, "Failed to unmount ${item.mountPoint}")
            return fail(R.string.error_unmount_failed)
        }

        val loopDetached = runCatching { detachLoop(item.loopDevice) }
        if (loopDetached.isFailure) {
            Log.w(
                TAG,
                "Loop detach failed for ${item.loopDevice}: ${loopDetached.exceptionOrNull()?.message}"
            )
        }

        File(item.mountPoint).delete() // rmdir semantics: only removes an empty dir
        File(snapshotFile).delete()
        refreshMountedImages(force = true)
        onRootsChanged(ctx)
        return MountResult.Unmounted(item.mountPoint)
    }

    suspend fun createStorageBind(
        stem: String,
        bindDir: String? = null,
        directMount: Boolean = false,
    ): BindResult = mountMutex.withLock {
        requireEnvReadyBind()?.let { return it }
        bindDir ?: return bindFail(R.string.error_bind_dir_not_specified)

        val rbd =
            resolveAndValidateBindDir(bindDir) ?: return bindFail(R.string.error_bind_dir_rejected)

        val safeStem = sanitizeStem(stem)
        val source = "$mountsDir/$safeStem"
        val target = if (directMount) rbd else "$rbd/$safeStem"

        if (!validatePath(source) || !validatePath(target) || !validatePath(rbd)) return bindFail(R.string.error_path_invalid_chars)

        val tgtArg = pathArg(target)
        val dirArg = pathArg(rbd)

        val mkdirDir = RootShell.cmd(
            "mkdir", ShellArg.literal("-p"), dirArg, chain = ShellCmd.chain(
                ShellCmd.of("chown", ShellArg.literal("1023:1023"), dirArg),
                ShellCmd.of("chmod", enumArg("775", ALLOWED_CHMOD_MODES), dirArg)
            )
        )
        if (mkdirDir.exitCode != 0) {
            Log.e(TAG, "Failed to create storage dir: $rbd")
            return bindFail(R.string.error_failed_create_storage_dir, rbd)
        }
        if (isMountedAt(target)) return BindResult.AlreadyExposed(target)
        if (directMount) {
            val ls = RootShell.cmd("ls", ShellArg.literal("-A"), tgtArg, ignoreError = true)
            if (ls.exitCode == 0 && ls.output.isNotBlank()) return bindFail(
                R.string.dialog_bind_nonempty, target
            )
        }
        val mkdirTgt = RootShell.cmd(
            "mkdir", ShellArg.literal("-p"), tgtArg, chain = ShellCmd.chain(
                ShellCmd.of("chown", ShellArg.literal("1023:1023"), tgtArg),
                ShellCmd.of("chmod", enumArg("775", ALLOWED_CHMOD_MODES), tgtArg)
            )
        )
        if (mkdirTgt.exitCode != 0) {
            Log.e(TAG, "Failed to create mount point dir: $target")
            return bindFail(R.string.error_failed_create_mount_point_dir, target)
        }

        val r = RootShell.cmd(
            "mount", ShellArg.literal("--bind"), pathArg(source), tgtArg, redirectErr = true
        )
        if (r.exitCode != 0) {
            Log.e(TAG, "Bind mount failed ($target): ${r.output}")
            return bindFail(R.string.error_bind_mount_failed, r.output)
        }

        RootShell.cmd(
            "chcon",
            ShellArg.literal("-R"),
            secontextArg("u:object_r:media_rw_data_file:s0"),
            tgtArg,
            ignoreError = true
        )
        return BindResult.Exposed(target)
    }

    suspend fun removeStorageBind(
        stem: String,
        bindDir: String? = null,
        directMount: Boolean = false,
    ): BindResult = mountMutex.withLock {
        bindDir ?: return BindResult.Skipped

        val rbd =
            resolveAndValidateBindDir(bindDir) ?: return bindFail(R.string.error_bind_dir_rejected)

        val target = if (directMount) rbd else "$rbd/${sanitizeStem(stem)}"
        if (!validatePath(target)) return bindFail(R.string.error_path_invalid_chars)

        val tgtArg = pathArg(target)
        if (isMountedAt(target)) {
            RootShell.cmd("umount", tgtArg, ignoreError = true)
        }
        RootShell.cmd("rmdir", tgtArg, ignoreError = true)
        cleanupEmptyBindDir(rbd)
        return BindResult.Removed(target)
    }

    private fun cleanupEmptyBindDir(bindDir: String) {
        val rm = RootShell.cmd("rmdir", pathArg(bindDir))
        if (rm.exitCode == 0) {
            Log.d(TAG, "Removed empty bind dir: $bindDir")
        }
    }

    private fun requireEnvReadyBind(): BindResult.Failure? =
        if (!envStatus.value.ready) BindResult.Failure(ctx.getString(R.string.error_env_not_ready)) else null

    // sparse images cannot be loop-mounted easily
    private fun isSparseImage(imagePath: String): Boolean {
        return try {
            RandomAccessFile(imagePath, "r").use { f ->
                val magic = ByteArray(4)
                f.readFully(magic)
                magic[0] == 0x3A.toByte() && magic[1] == 0xFF.toByte() &&
                        magic[2] == 0x26.toByte() && magic[3] == 0xED.toByte()
            }
        } catch (e: Exception) {
            Log.w(TAG, "isSparseImage: read failed for $imagePath: ${e.message}")
            false
        }
    }

    // canonicalizes and double-checks [bindDir] against [validateBindDir] to detect symlink
    // races between the two resolutions. Returns null if the path is rejected at any stage.
    private fun resolveAndValidateBindDir(bindDir: String): String? {
        if (validateBindDir(ctx, bindDir) != null) {
            Log.w(TAG, "bindDir rejected before canonicalization: $bindDir")
            return null
        }
        val resolved = try {
            File(bindDir).canonicalPath
        } catch (e: Exception) {
            Log.e(TAG, "canonicalPath failed for $bindDir: ${e.message}")
            return null
        }
        if (validateBindDir(ctx, resolved) != null) {
            Log.w(TAG, "bindDir rejected after canonicalization: $resolved")
            return null
        }
        val verified = try {
            File(resolved).canonicalPath
        } catch (e: Exception) {
            Log.e(TAG, "re-resolve failed for $resolved: ${e.message}")
            return null
        }
        if (verified != resolved) {
            Log.e(TAG, "bindDir changed between resolutions, possible symlink race: $bindDir")
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
        preservePermissions: Boolean = false,
    ): MountResult {
        val mp = "$mountsDir/$stem"
        if (isMountedAt(mp)) {
            refreshMountedImages(force = true)
            return MountResult.AlreadyMounted(mp)
        }

        val opts = buildMountOpts(fsType, mode)
        val result = doMount(ctx, imagePath, mp, fsType, opts, busyboxBin, partOffset)

        if (result.isSuccess) {
            if (!isMountedAt(mp)) {
                Log.e(TAG, "Mount reported success but $mp absent from /proc/mounts")
                detachStaleLoops(imagePath)
                return fail(R.string.error_mount_not_visible)
            }
            if (fsType.posixPermissions) {
                if (preservePermissions) {
                    val snapshotFile = "$mountsDir/.$stem.perm"
                    snapshotPermissions(mp, snapshotFile, busyboxBin).onFailure {
                        Log.w(TAG, "snapshotPermissions failed for $mp: ${it.message}")
                    }
                }
                makeAccessible(mp, mountsDir, busyboxBin).onFailure {
                    Log.w(TAG, "makeAccessible failed for $mp: ${it.message}")
                }
            }
            refreshMountedImages(force = true)
            onRootsChanged(ctx)
            return MountResult.Mounted(mp)
        }

        Log.e(TAG, "doMount failed for $imagePath -> $mp: ${result.exceptionOrNull()?.message}")
        val cause = result.exceptionOrNull()?.message ?: ""
        return MountResult.Failure(
            ctx.getString(
                R.string.error_mount_failed_output, cause.ifBlank { "unknown" })
        )
    }
}
