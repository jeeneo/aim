// SPDX-License-Identifier: GPL-3.0-or-later

package org.codeberg.aimapp.utils.mounts

import android.content.Context
import android.util.Log
import org.codeberg.aimapp.R
import org.codeberg.aimapp.utils.shell.LOOP_DEV_REGEX
import org.codeberg.aimapp.utils.shell.RootShell
import org.codeberg.aimapp.utils.shell.ShellArg
import org.codeberg.aimapp.utils.shell.ShellCmd
import org.codeberg.aimapp.utils.shell.enumArg
import org.codeberg.aimapp.utils.shell.loopDevArg
import org.codeberg.aimapp.utils.shell.mountOptsArg
import org.codeberg.aimapp.utils.shell.numArg
import org.codeberg.aimapp.utils.shell.pathArg
import org.codeberg.aimapp.utils.shell.resolvedBusyboxPath
import org.codeberg.aimapp.utils.shell.secontextArg
import java.io.File

private const val TAG = "MountOps"

val ALLOWED_CHMOD_MODES = setOf("777", "775", "664")
private fun detailOrUnknown(output: String): String = output.trim().ifBlank { "no command output" }

fun buildMountOpts(fsType: FsType, mode: MountMode): String {
    if (fsType.readOnly) return "ro,nosuid,nodev,noexec"
    val base =
        if (mode == MountMode.PUBLIC) "rw,nosuid,nodev,noexec,noatime" else "rw,nosuid,nodev,noexec"
    return when (fsType) {
        FsType.EXT4 -> base
        FsType.VFAT -> "$base,uid=0,gid=0,fmask=0000,dmask=0000,allow_utime=0022,iocharset=utf8"
        FsType.EXFAT -> "$base,uid=0,gid=0,fmask=0000,dmask=0000"
        FsType.ISO9660 -> error("ISO9660 must be read-only; FsType.readOnly invariant violated")
        FsType.NTFS -> "$base,uid=0,gid=0,fmask=0000,dmask=0000"
        is FsType.OTHER -> base
    }
}

private fun checkKernelFs(fsType: String): Boolean {
    val fsArg = ShellArg.of(fsType)
    if (RootShell.cmd(
            "grep",
            ShellArg.literal("-qw"),
            fsArg,
            pathArg("/proc/filesystems")
        ).exitCode == 0
    ) return true
    Log.d(TAG, "$fsType not in /proc/filesystems")
    return false
}

fun doMount(
    ctx: Context,
    imagePath: String,
    mountPoint: String,
    fsType: FsType,
    mountOpts: String,
    partOffset: Long = 0
): Result<String> {
    val isPartition = partOffset > 0
    Log.d(
        TAG,
        "img=$imagePath, mp=$mountPoint, fs=${fsType.mountType}, opts=$mountOpts" + if (isPartition) ", offset=$partOffset" else ""
    )

    val imgPath = pathArg(imagePath)
    val mp = pathArg(mountPoint)
    val fsArg = ShellArg.of(fsType.mountType)
    val optsArg = mountOptsArg(mountOpts)
    if (!checkKernelFs(fsType.mountType)) {
        Log.w(TAG, "kernel does not support ${fsType.mountType}")
        return Result.failure(
            Exception(
                ctx.getString(
                    R.string.error_kernel_no_fs, fsType.mountType
                )
            )
        )
    }

    // mount points live under the app-owned mountsDir
    val mpDir = File(mountPoint)
    if (!mpDir.isDirectory && !mpDir.mkdirs()) return Result.failure(
        Exception(ctx.getString(R.string.error_failed_create_mount_point_dir, mountPoint))
    )

    var directMountError: String? = null
    if (!isPartition) {
        val loopOpts = mountOptsArg("$mountOpts,loop")
        val direct = RootShell.cmd(
            "mount",
            ShellArg.literal("-t"),
            fsArg,
            ShellArg.literal("-o"),
            loopOpts,
            imgPath,
            mp,
            redirectErr = true
        )
        Log.d(TAG, "direct: exit=${direct.exitCode}, out=${direct.output}")
        if (direct.exitCode == 0) return Result.success("Mounted at $mountPoint")
        directMountError = detailOrUnknown(direct.output)
        Log.d(TAG, "direct mount failed: $directMountError")
    }

    Log.d(
        TAG, if (isPartition) "using losetup with offset" else "direct failed, falling to losetup"
    )
    var attachedLoop: String? = null
    var mountSucceeded = false
    val hasSystemLosetup =
        RootShell.cmd("test", ShellArg.literal("-x"), pathArg("/system/bin/losetup")).exitCode == 0
    if (!hasSystemLosetup) Log.d(TAG, "system losetup not found, falling back to busybox")
    val loopTool = if (hasSystemLosetup) "" else resolvedBusyboxPath
    try {
        val findFree = RootShell.cmd(
            "losetup", ShellArg.literal("-f"), busyboxBin = loopTool
        )
        val loopDev = findFree.output.lineSequence().firstOrNull()?.trim()
            ?.takeIf { it.matches(LOOP_DEV_REGEX) } ?: return failCleanup(
            mountPoint,
            null,
            ctx.getString(R.string.error_no_free_loop, detailOrUnknown(findFree.output))
        )
        Log.d(TAG, "loop=$loopDev")
        attachedLoop = loopDev
        val loopArg = loopDevArg(loopDev)

        val loopIdx = loopDev.substringAfterLast("loop", "").toIntOrNull()
        if (loopIdx == null) {
            Log.w(TAG, "could not parse loop index from '$loopDev', skipping mknod")
        } else {
            if (RootShell.cmd("test", ShellArg.literal("-b"), loopArg).exitCode != 0) {
                Log.d(TAG, "creating block device node for loop$loopIdx")
                RootShell.cmd(
                    "mknod",
                    loopArg,
                    ShellArg.literal("b"),
                    numArg(7),
                    numArg(loopIdx),
                    ignoreError = true
                )
            }
        }

        val losetupArgs = buildList {
            if (isPartition) {
                add(ShellArg.literal("-o"))
                add(numArg(partOffset))
            }
            add(loopArg)
            add(imgPath)
        }
        Log.d(TAG, "losetup: dev=$loopDev" + if (isPartition) ", offset=$partOffset" else "")
        val attach = RootShell.cmd(
            "losetup", *losetupArgs.toTypedArray(), busyboxBin = loopTool, redirectErr = true
        )
        Log.d(TAG, "losetup: exit=${attach.exitCode}, out=${attach.output.trim().take(200)}")
        if (attach.exitCode != 0) return failCleanup(
            mountPoint,
            null,
            ctx.getString(R.string.error_failed_attach_loop, detailOrUnknown(attach.output))
        )
        Log.d(TAG, "mount: dev=$loopDev -> $mountPoint, fs=${fsType.mountType}")
        val mount = RootShell.cmd(
            "mount",
            ShellArg.literal("-t"),
            fsArg,
            ShellArg.literal("-o"),
            optsArg,
            loopArg,
            mp,
            redirectErr = true
        )
        Log.d(TAG, "mount: exit=${mount.exitCode}, out=${mount.output.trim().take(200)}")
        if (mount.exitCode != 0) {
            val fullDetail = listOfNotNull(
                directMountError, detailOrUnknown(mount.output)
            ).joinToString(" → then ")
            return failCleanup(
                mountPoint,
                loopDev,
                ctx.getString(R.string.error_mount_failed_output, fullDetail)
            )
        }
        mountSucceeded = true
        return Result.success("Mounted at $mountPoint" + if (isPartition) " (partition at offset $partOffset)" else " using $loopDev")
    } finally {
        if (!mountSucceeded) {
            attachedLoop?.let { dev ->
                RootShell.cmd(
                    "losetup",
                    ShellArg.literal("-d"),
                    loopDevArg(dev),
                    busyboxBin = loopTool,
                    ignoreError = true
                )
            }
            File(mountPoint).delete() // rmdir semantics: only removes an empty dir
        }
    }
}

// chmod/chcon on POSIX mounts so the app process can access files
fun makeAccessible(mountPoint: String, mountsDir: String): Result<String> {
    Log.d(TAG, "makeAccessible: $mountPoint")
    val mpArg = pathArg(mountPoint)
    val mountsDirArg = pathArg(mountsDir)
    val parentCtx = RootShell.cmd(
        "ls",
        ShellArg.literal("-dZ"),
        mountsDirArg,
        pipeInto = ShellCmd.of("awk", ShellArg.of("{print $1}"))
    ).output.trim().takeIf { it.matches(Regex("^[a-zA-Z0-9_:,.]+$")) && ':' in it } ?: run {
        Log.w(TAG, "makeAccessible: could not parse SELinux context, using fallback")
        APP_DATA_SECONTEXT
    }

    val chmodDirs = RootShell.cmd(
        "find",
        mpArg,
        ShellArg.literal("-type"),
        ShellArg.literal("d"),
        ShellArg.literal("-exec"),
        ShellArg.literal("chmod"),
        enumArg("777", ALLOWED_CHMOD_MODES),
        ShellArg.literal("{}"),
        ShellArg.literal("+")
    )
    if (chmodDirs.exitCode != 0) {
        Log.w(TAG, "chmod (dirs) failed: ${chmodDirs.output}")
        return Result.failure(Exception("Failed to set directory permissions on $mountPoint"))
    }

    val chmodFiles = RootShell.cmd(
        "find",
        mpArg,
        ShellArg.literal("-type"),
        ShellArg.literal("f"),
        ShellArg.literal("-exec"),
        ShellArg.literal("chmod"),
        enumArg("664", ALLOWED_CHMOD_MODES),
        ShellArg.literal("{}"),
        ShellArg.literal("+")
    )
    if (chmodFiles.exitCode != 0) {
        Log.w(TAG, "chmod (files) failed: ${chmodFiles.output}")
        return Result.failure(Exception("Failed to set file permissions on $mountPoint"))
    }

    val chcon = RootShell.cmd(
        "chcon",
        ShellArg.literal("-Rh"),
        secontextArg(parentCtx),
        mpArg,
        ignoreError = true
    )
    if (chcon.exitCode != 0) Log.w(TAG, "chcon failed: ${chcon.output}")
    return Result.success("permissions set")
}

private val OCTAL_MODE = Regex("^[0-7]{3,4}$")

internal data class PermEntry(val path: String, val uid: Int, val gid: Int, val mode: String)

fun snapshotPermissions(mountPoint: String, snapshotFile: String): Result<String> {
    val snapshotArg = ShellArg.of(snapshotFile)
    File(snapshotFile).apply { parentFile?.mkdirs() }
    val find = RootShell.cmd(
        "find",
        pathArg(mountPoint),
        ShellArg.literal("-not"),
        ShellArg.literal("-type"),
        ShellArg.literal("l"),
        ShellArg.literal("-exec"),
        ShellArg.literal("stat"),
        ShellArg.literal("-c"),
        ShellArg.of("%n\t%u\t%g\t%a"),
        ShellArg.literal("{}"),
        ShellArg.literal("+"),
        redirectErr = true,
        outputTo = snapshotArg.quoted
    )
    if (find.exitCode != 0) {
        Log.w(TAG, "snapshotPermissions: find/stat failed: ${find.output.take(200)}")
        return Result.failure(Exception("Failed to snapshot permissions on $mountPoint"))
    }
    // the snapshot lives in the app's own storage; count entries directly instead of wc -l
    val count = try {
        File(snapshotFile).useLines { lines -> lines.count { it.isNotBlank() } }
    } catch (e: Exception) {
        Log.w(TAG, "snapshotPermissions: could not read snapshot: ${e.message}")
        0
    }
    if (count <= 0) {
        Log.w(TAG, "snapshotPermissions: snapshot empty/invalid at $snapshotFile")
        return Result.failure(Exception("Failed to snapshot permissions on $mountPoint"))
    }
    Log.d(TAG, "snapshotPermissions: saved $count entries to $snapshotFile")
    return Result.success(snapshotFile)
}

internal fun parseSnapshot(raw: String, mountPoint: String): List<PermEntry> {
    val lines = raw.lines()
    val entries = ArrayList<PermEntry>(lines.size)
    for (rec in lines) {
        if (rec.isBlank()) continue
        val f = rec.split('\t')
        val entry = if (f.size != 4) null else run {
            val (path, uidStr, gidStr, mode) = f
            val uid = uidStr.toIntOrNull() ?: return@run null
            val gid = gidStr.toIntOrNull() ?: return@run null
            if (!OCTAL_MODE.matches(mode)) return@run null
            if (path != mountPoint && !isPathUnderMount(path, mountPoint)) return@run null
            PermEntry(path, uid, gid, mode)
        }
        if (entry != null) entries.add(entry)
    }
    return entries
}

private fun isPathUnderMount(path: String, mountPoint: String): Boolean {
    if (!path.startsWith("$mountPoint/")) return false
    return path.substring(mountPoint.length + 1).split('/').none { it == ".." || it == "." }
}

fun restorePermissions(
    mountPoint: String,
    snapshotFile: String,
    preservePermissions: Boolean,
): Result<String> {
    val snapFile = File(snapshotFile)
    if (!snapFile.exists() || !preservePermissions) {
        Log.w(
            TAG,
            "restorePermissions: resetting permissions (preservePermissions: $preservePermissions, hasSnapshot: ${snapFile.exists()})"
        )
        return resetPermissions(mountPoint)
    }
    val raw = runCatching { snapFile.readText() }.getOrElse {
        return Result.failure(Exception("Failed to read permission snapshot: ${it.message}"))
    }
    val entries = parseSnapshot(raw, mountPoint)
    if (entries.isEmpty()) return Result.success("nothing to restore")
    val groups = entries.groupBy { Triple(it.uid, it.gid, it.mode) }
    Log.d(TAG, "restorePermissions: restoring ${entries.size} entries in ${groups.size} groups")
    for ((key, group) in groups) {
        val (uid, gid, mode) = key
        val allPaths = group.map { it.path }
        for (batch in allPaths.chunked(500)) {
            val paths = batch.map { pathArg(it) }.toTypedArray()
            val chown = RootShell.cmd(
                "chown",
                ShellArg.literal("-h"),
                ShellArg.of("$uid:$gid"),
                *paths,
                redirectErr = true
            )
            if (chown.exitCode != 0) {
                return Result.failure(
                    Exception(
                        "Permission restore aborted at chown $uid:$gid: ${
                            chown.output.take(
                                200
                            )
                        }"
                    )
                )
            }
            val chmod = RootShell.cmd(
                "find",
                *paths,
                ShellArg.literal("-maxdepth"),
                ShellArg.literal("0"),
                ShellArg.literal("-not"),
                ShellArg.literal("-type"),
                ShellArg.literal("l"),
                ShellArg.literal("-exec"),
                ShellArg.literal("chmod"),
                ShellArg.of(mode),
                ShellArg.literal("{}"),
                ShellArg.literal("+"),
                redirectErr = true
            )
            if (chmod.exitCode != 0) {
                return Result.failure(
                    Exception(
                        "Permission restore aborted at chmod $mode: ${
                            chmod.output.take(
                                200
                            )
                        }"
                    )
                )
            }
        }
    }
    return Result.success("restored ${entries.size} entries in ${groups.size} groups")
}

fun resetPermissions(mountPoint: String): Result<String> {
    Log.d(TAG, "resetPermissions: resetting permissions for: $mountPoint")
    val mpArg = pathArg(mountPoint)
    val chown = RootShell.cmd(
        "chown",
        ShellArg.literal("-Rh"),
        ShellArg.literal(SYSTEM_OWNERSHIP),
        mpArg
    )
    if (chown.exitCode != 0) {
        Log.w(TAG, "chown failed: ${chown.output}")
        return Result.failure(Exception("Failed to restore ownership on $mountPoint"))
    }

    listOf(Pair("d", DEFAULT_DIR_MODE), Pair("f", "664")).forEach { (type, mode) ->
        val res = RootShell.cmd(
            "find",
            mpArg,
            ShellArg.literal("-type"),
            ShellArg.literal(type),
            ShellArg.literal("-not"),
            ShellArg.literal("-type"),
            ShellArg.literal("l"),
            ShellArg.literal("-exec"),
            ShellArg.literal("chmod"),
            enumArg(mode, ALLOWED_CHMOD_MODES),
            ShellArg.literal("{}"),
            ShellArg.literal("+")
        )
        if (res.exitCode != 0) {
            Log.w(TAG, "chmod restore ($type) failed: ${res.output}")
            return Result.failure(Exception("Failed to restore permissions ($type) on $mountPoint"))
        }
    }
    Log.d(TAG, "resetPermissions: restore done for $mountPoint")
    return Result.success("permissions restored")
}

fun failCleanup(mp: String, loop: String?, msg: String): Result<String> {
    Log.w(TAG, "failCleanup: $msg (loop=$loop, mp=$mp)")
    loop?.let { dev ->
        RootShell.cmd(
            "losetup",
            ShellArg.literal("-d"),
            loopDevArg(dev),
            ignoreError = true
        )
    }
    File(mp).delete() // rmdir semantics: only removes an empty dir
    return Result.failure(Exception(msg))
}

