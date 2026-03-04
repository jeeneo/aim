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
import org.codeberg.dryerlint.aim.FsType
import org.codeberg.dryerlint.aim.MountMode
import org.codeberg.dryerlint.aim.OpResult
import org.codeberg.dryerlint.aim.R
import timber.log.Timber

private const val TAG = "MountOps"

private val ALLOWED_FS_TYPES = setOf("ext4", "vfat", "exfat", "iso9660")
private val ALLOWED_CHMOD_MODES = setOf("775", "664", "777")
private fun detailOrUnknown(output: String): String = output.trim().ifBlank { "no command output" }

fun buildMountOpts(fsType: FsType, mode: MountMode): String {
    if (fsType.readOnly) return "ro,nosuid,nodev,noexec"
    val base =
        if (mode == MountMode.PUBLIC) "rw,nosuid,nodev,noexec,noatime" else "rw,nosuid,nodev,noexec"
    return when (fsType) {
        FsType.EXT4 -> base
        FsType.VFAT -> "$base,uid=0,gid=0,fmask=0000,dmask=0000,allow_utime=0022,iocharset=utf8"
        FsType.EXFAT -> "$base,uid=0,gid=0,fmask=0000,dmask=0000"
        FsType.ISO9660 -> "ro,nosuid,nodev,noexec" // never reached, but exhaustive
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
    Timber.tag(TAG).d("$fsType not in /proc/filesystems")
    return false

    // RootShell.cmd("modprobe", fsArg, suppressErr = true, ignoreError = true)
    // RootShell.cmd("insmod", fsArg, suppressErr = true, ignoreError = true)
    // return RootShell.cmd("grep", ShellArg.literal("-qw"), fsArg, pathArg("/proc/filesystems"), busyboxBin = busyboxBin).exitCode == 0
}

// try direct mount, then losetup fallback.
// [partOffset]/[partSize]: when mounting a partition inside a disk image, specify the byte offset and size.
fun doMount(
    ctx: Context,
    imagePath: String,
    mountPoint: String,
    fsType: FsType,
    mountOpts: String,
    busyboxBin: String,
    partOffset: Long = 0,
    partSize: Long = 0
): OpResult {
    val isPartition = partOffset > 0
    Timber.tag(TAG).d(
        "img=$imagePath, mp=$mountPoint, fs=${fsType.mountType}, opts=$mountOpts" +
            if (isPartition) ", offset=$partOffset, size=$partSize" else ""
    )

    val imgPath = pathArg(imagePath)
    val mp = pathArg(mountPoint)
    val fsArg = enumArg(fsType.mountType, ALLOWED_FS_TYPES)
    val optsArg = mountOptsArg(mountOpts)

    // for fs types that may not be built-in, check the kernel module is loaded
    if (!checkKernelFs(fsType.mountType, busyboxBin)) {
        Timber.tag(TAG).w("kernel does not support ${fsType.mountType}")
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

    // for partitioned images, skip the direct mount (it requires offset) and go straight to losetup
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
        Timber.tag(TAG).d("direct: exit=${direct.exitCode}, out=${direct.output}")
        if (direct.exitCode == 0) return OpResult.success("Mounted at $mountPoint")
    }

    // fallback: manual losetup (required when mounting a partition inside a disk image)
    Timber.tag(TAG).d(
        if (isPartition) "using losetup with offset" else "direct failed, falling to losetup"
    )
    val loopDev = RootShell.cmd(
        "losetup", ShellArg.literal("-f"), busyboxBin = busyboxBin
    ).output.lineSequence().firstOrNull()?.trim() ?: return cleanupAndFail(
        mountPoint, null, ctx.getString(R.string.error_no_free_loop), busyboxBin
    )
    Timber.tag(TAG).d("loop=$loopDev")
    val loopArg = loopDevArg(loopDev)
    loopDev.substringAfterLast("loop", "").toIntOrNull()?.let { idx ->
        if (RootShell.cmd("test", ShellArg.literal("-b"), loopArg).exitCode != 0) {
            Timber.tag(TAG).d("creating block device node for loop$idx")
            RootShell.cmd(
                "mknod",
                loopArg,
                ShellArg.literal("b"),
                numArg(7),
                numArg(idx),
                busyboxBin = busyboxBin,
                suppressErr = true,
                ignoreError = true
            )
        }
    }

    // losetup with optional offset
    val losetupArgs = buildList {
        if (isPartition) {
            add(ShellArg.literal("-o"))
            add(numArg(partOffset))
        }
        add(loopArg)
        add(imgPath)
    }

    Timber.tag(TAG).d("losetup: dev=$loopDev" + if (isPartition) ", offset=$partOffset" else "")
    val attach = RootShell.cmd(
        "losetup", *losetupArgs.toTypedArray(), busyboxBin = busyboxBin, redirectErr = true
    )
    Timber.tag(TAG).d("losetup: exit=${attach.exitCode}, out=${attach.output.trim().take(200)}")
    if (attach.exitCode != 0) return cleanupAndFail(
        mountPoint,
        null,
        ctx.getString(R.string.error_failed_attach_loop, detailOrUnknown(attach.output)),
        busyboxBin
    )
    Timber.tag(TAG).d("mount: dev=$loopDev -> $mountPoint, fs=${fsType.mountType}")
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
    Timber.tag(TAG).d("mount: exit=${mount.exitCode}, out=${mount.output.trim().take(200)}")
    if (mount.exitCode != 0) return cleanupAndFail(
        mountPoint,
        loopDev,
        ctx.getString(R.string.error_mount_failed_output, detailOrUnknown(mount.output)),
        busyboxBin
    )
    return OpResult.success("Mounted at $mountPoint" + if (isPartition) " (partition at offset $partOffset)" else " using $loopDev")
}

// chmod/chcon on POSIX mounts so the app process can access files
fun makeAccessible(mountPoint: String, mountsDir: String, busyboxBin: String) {
    Timber.tag(TAG).d("makeAccessible: $mountPoint")
    val mpArg = pathArg(mountPoint)
    val mountsDirArg = pathArg(mountsDir)
    val parentCtx = RootShell.cmd(
        "ls",
        ShellArg.literal("-dZ"),
        mountsDirArg,
        pipeInto = ShellCmd.of("awk", ShellArg.of("{print \$1}"), busyboxBin = busyboxBin)
    ).output.trim().takeIf { it.matches(Regex("^[a-zA-Z0-9_:,.]+$")) && ':' in it }
        ?: "u:object_r:app_data_file:s0"
    RootShell.cmd(
        "chmod",
        ShellArg.literal("-R"),
        enumArg("777", ALLOWED_CHMOD_MODES),
        mpArg,
        chain = ShellCmd.of("chcon", ShellArg.literal("-Rh"), secontextArg(parentCtx), mpArg)
    )
}

// restore ownership (1000:1000) and permissions before unmount
fun restorePermissions(mountPoint: String, busyboxBin: String) {
    Timber.tag(TAG).d("restorePerms: $mountPoint")
    val mpArg = pathArg(mountPoint)
    RootShell.cmd("chown", ShellArg.literal("-R"), ShellArg.literal("1000:1000"), mpArg)
    listOf(Pair("d", "775"), Pair("f", "664")).forEach { (type, mode) ->
        RootShell.cmd(
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
    }
}

fun cleanupAndFail(mp: String, loop: String?, msg: String, busyboxBin: String): OpResult {
    Timber.tag(TAG).w("cleanupAndFail: $msg (loop=$loop, mp=$mp)")
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
