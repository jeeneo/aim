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

package org.codeberg.aimapp.utils

import android.content.Context
import org.codeberg.aimapp.FsType
import org.codeberg.aimapp.L
import org.codeberg.aimapp.MountMode
import org.codeberg.aimapp.OpResult
import org.codeberg.aimapp.R

private const val TAG = "MountOps"

private val ALLOWED_FS_TYPES = setOf("ext4", "vfat", "exfat", "iso9660")
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
    }
}

private fun checkKernelFs(fsType: String, busyboxBin: String): Boolean {
    val fsArg = enumArg(fsType, ALLOWED_FS_TYPES)
    if (RootShell.cmd(
            "grep",
            ShellArg.literal("-qw"),
            fsArg,
            pathArg("/proc/filesystems"),
            busyboxBin = busyboxBin
        ).exitCode == 0
    ) return true
    L.d(TAG, "$fsType not in /proc/filesystems")
    return false
}

fun doMount(
    ctx: Context,
    imagePath: String,
    mountPoint: String,
    fsType: FsType,
    mountOpts: String,
    busyboxBin: String,
    partOffset: Long = 0
): OpResult {
    val isPartition = partOffset > 0
    L.d(
        TAG,
        "img=$imagePath, mp=$mountPoint, fs=${fsType.mountType}, opts=$mountOpts" + if (isPartition) ", offset=$partOffset" else ""
    )

    val imgPath = pathArg(imagePath)
    val mp = pathArg(mountPoint)
    val fsArg = enumArg(fsType.mountType, ALLOWED_FS_TYPES)
    val optsArg = mountOptsArg(mountOpts)
    if (!checkKernelFs(fsType.mountType, busyboxBin)) {
        L.w(TAG, "kernel does not support ${fsType.mountType}")
        return OpResult.failure(
            Exception(
                ctx.getString(
                    R.string.error_kernel_no_fs, fsType.mountType
                )
            )
        )
    }

    if (RootShell.cmd("mkdir", ShellArg.literal("-p"), mp).exitCode != 0) return OpResult.failure(
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
            busyboxBin = busyboxBin,
            redirectErr = true
        )
        L.d(TAG, "direct: exit=${direct.exitCode}, out=${direct.output}")
        if (direct.exitCode == 0) return OpResult.success("Mounted at $mountPoint")
        directMountError = detailOrUnknown(direct.output)
        L.d(TAG, "direct mount failed: $directMountError")
    }

    L.d(TAG, if (isPartition) "using losetup with offset" else "direct failed, falling to losetup")
    var attachedLoop: String? = null
    var mountSucceeded = false
    try {
        val loopDev = RootShell.cmd(
            "losetup", ShellArg.literal("-f"), busyboxBin = busyboxBin
        ).output.lineSequence().firstOrNull()?.trim() ?: return failCleanup(
            mountPoint, null, ctx.getString(R.string.error_no_free_loop), busyboxBin
        )
        L.d(TAG, "loop=$loopDev")
        attachedLoop = loopDev
        val loopArg = loopDevArg(loopDev)

        val loopIdx = loopDev.substringAfterLast("loop", "").toIntOrNull()
        if (loopIdx == null) {
            L.w(TAG, "could not parse loop index from '$loopDev', skipping mknod")
        } else {
            if (RootShell.cmd("test", ShellArg.literal("-b"), loopArg).exitCode != 0) {
                L.d(TAG, "creating block device node for loop$loopIdx")
                RootShell.cmd(
                    "mknod",
                    loopArg,
                    ShellArg.literal("b"),
                    numArg(7),
                    numArg(loopIdx),
                    busyboxBin = busyboxBin,
                    suppressErr = true,
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
        L.d(TAG, "losetup: dev=$loopDev" + if (isPartition) ", offset=$partOffset" else "")
        val attach = RootShell.cmd(
            "losetup", *losetupArgs.toTypedArray(), busyboxBin = busyboxBin, redirectErr = true
        )
        L.d(TAG, "losetup: exit=${attach.exitCode}, out=${attach.output.trim().take(200)}")
        if (attach.exitCode != 0) return failCleanup(
            mountPoint,
            null,
            ctx.getString(R.string.error_failed_attach_loop, detailOrUnknown(attach.output)),
            busyboxBin
        )
        L.d(TAG, "mount: dev=$loopDev -> $mountPoint, fs=${fsType.mountType}")
        val mount = RootShell.cmd(
            "mount",
            ShellArg.literal("-t"),
            fsArg,
            ShellArg.literal("-o"),
            optsArg,
            loopArg,
            mp,
            busyboxBin = busyboxBin,
            redirectErr = true
        )
        L.d(TAG, "mount: exit=${mount.exitCode}, out=${mount.output.trim().take(200)}")
        if (mount.exitCode != 0) {
            val fullDetail = listOfNotNull(
                directMountError, detailOrUnknown(mount.output)
            ).joinToString(" → then ")
            return failCleanup(
                mountPoint,
                loopDev,
                ctx.getString(R.string.error_mount_failed_output, fullDetail),
                busyboxBin
            )
        }
        mountSucceeded = true
        return OpResult.success("Mounted at $mountPoint" + if (isPartition) " (partition at offset $partOffset)" else " using $loopDev")
    } finally {
        if (!mountSucceeded) {
            attachedLoop?.let { dev ->
                RootShell.cmd(
                    "losetup",
                    ShellArg.literal("-d"),
                    loopDevArg(dev),
                    busyboxBin = busyboxBin,
                    suppressErr = true,
                    ignoreError = true
                )
            }
            RootShell.cmd("rmdir", pathArg(mountPoint), suppressErr = true, ignoreError = true)
        }
    }
}

// chmod/chcon on POSIX mounts so the app process can access files
fun makeAccessible(mountPoint: String, mountsDir: String, busyboxBin: String): OpResult {
    L.d(TAG, "makeAccessible: $mountPoint")
    val mpArg = pathArg(mountPoint)
    val mountsDirArg = pathArg(mountsDir)
    val parentCtx = RootShell.cmd(
        "ls",
        ShellArg.literal("-dZ"),
        mountsDirArg,
        pipeInto = ShellCmd.of("awk", ShellArg.of("{print $1}"), busyboxBin = busyboxBin)
    ).output.trim().takeIf { it.matches(Regex("^[a-zA-Z0-9_:,.]+$")) && ':' in it } ?: run {
        L.w(TAG, "makeAccessible: could not parse SELinux context, using fallback")
        "u:object_r:app_data_file:s0"
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
        ShellArg.literal("+"),
        busyboxBin = busyboxBin
    )
    if (chmodDirs.exitCode != 0) {
        L.w(TAG, "chmod (dirs) failed: ${chmodDirs.output}")
        return OpResult.failure(Exception("Failed to set directory permissions on $mountPoint"))
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
        ShellArg.literal("+"),
        busyboxBin = busyboxBin
    )
    if (chmodFiles.exitCode != 0) {
        L.w(TAG, "chmod (files) failed: ${chmodFiles.output}")
        return OpResult.failure(Exception("Failed to set file permissions on $mountPoint"))
    }

    val chcon = RootShell.cmd(
        "chcon",
        ShellArg.literal("-Rh"),
        secontextArg(parentCtx),
        mpArg,
        busyboxBin = busyboxBin,
        suppressErr = true,
        ignoreError = true
    )
    if (chcon.exitCode != 0) L.w(TAG, "chcon failed: ${chcon.output}")
    return OpResult.success("permissions set")
}

// restore ownership (1000:1000) and permissions before unmount
fun restorePermissions(mountPoint: String, busyboxBin: String): OpResult {
    L.d(TAG, "restorePerms: $mountPoint")
    val mpArg = pathArg(mountPoint)
    val chown = RootShell.cmd(
        "chown",
        ShellArg.literal("-R"),
        ShellArg.literal("1000:1000"),
        mpArg,
        busyboxBin = busyboxBin
    )
    if (chown.exitCode != 0) L.w(TAG, "chown failed: ${chown.output}")

    var failed = false
    listOf(Pair("d", "775"), Pair("f", "664")).forEach { (type, mode) ->
        val res = RootShell.cmd(
            "find",
            mpArg,
            ShellArg.literal("-type"),
            ShellArg.literal(type),
            ShellArg.literal("-exec"),
            ShellArg.literal("chmod"),
            enumArg(mode, ALLOWED_CHMOD_MODES),
            ShellArg.literal("{}"),
            ShellArg.literal("+"),
            busyboxBin = busyboxBin
        )
        if (res.exitCode != 0) {
            L.w(TAG, "chmod restore ($type) failed: ${res.output}")
            failed = true
        }
    }
    return if (failed) OpResult.failure(Exception("Some permission restore operations failed")) else OpResult.success(
        "permissions restored"
    )
}

fun failCleanup(mp: String, loop: String?, msg: String, busyboxBin: String): OpResult {
    L.w(TAG, "failCleanup: $msg (loop=$loop, mp=$mp)")
    loop?.let { dev ->
        RootShell.cmd(
            "losetup",
            ShellArg.literal("-d"),
            loopDevArg(dev),
            busyboxBin = busyboxBin,
            suppressErr = true,
            ignoreError = true
        )
    }
    RootShell.cmd("rmdir", pathArg(mp), suppressErr = true, ignoreError = true)
    return OpResult.failure(Exception(msg))
}
