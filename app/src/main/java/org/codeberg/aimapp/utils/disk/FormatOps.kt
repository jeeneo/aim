// SPDX-License-Identifier: GPL-3.0-or-later

package org.codeberg.aimapp.utils.disk

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import org.codeberg.aimapp.R
import org.codeberg.aimapp.utils.mounts.OpResult
import org.codeberg.aimapp.utils.paths.validatePath
import org.codeberg.aimapp.utils.shell.RootShell
import org.codeberg.aimapp.utils.shell.ShellArg
import org.codeberg.aimapp.utils.shell.ShellCmd
import org.codeberg.aimapp.utils.shell.ShellResult
import org.codeberg.aimapp.utils.shell.pathArg
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

private const val TAG = "FormatOps"

private data class MkfsLocation(val path: String, val isBusyboxApplet: Boolean)

@SuppressLint("SdCardPath")
private val FORMAT_ALLOWED_PREFIXES = listOf(
    "/storage/emulated/",
    "/data/media/",
    "/sdcard/",
    "/mnt/media_rw/",
)

// format an image, must NOT be mounted
fun formatImage(
    ctx: Context, path: String, ready: Boolean, busyboxBin: String, fsType: String = "ext4"
): OpResult {
    if (!ready) return OpResult.failure(Exception(ctx.getString(R.string.error_env_not_ready)))
    val imagePath = path.trim()
    if (imagePath.isBlank() || !imagePath.endsWith(
            ".img", ignoreCase = true
        )
    ) return OpResult.failure(Exception(ctx.getString(R.string.error_only_img_format_supported)))
    if (imagePath.endsWith(
            ".iso", ignoreCase = true
        )
    ) return OpResult.failure(Exception(ctx.getString(R.string.error_iso_read_only)))
    if (!validatePath(imagePath)) return OpResult.failure(Exception(ctx.getString(R.string.error_path_invalid_chars)))
    val canonical = try {
        File(imagePath).canonicalPath
    } catch (e: Exception) {
        return OpResult.failure(
            Exception(
                ctx.getString(
                    R.string.error_resolve_image_path, e.message ?: ""
                )
            )
        )
    }
    if (!canonical.endsWith(
            ".img", ignoreCase = true
        )
    ) return OpResult.failure(Exception(ctx.getString(R.string.error_resolved_not_img)))
    if (!validatePath(canonical)) return OpResult.failure(Exception(ctx.getString(R.string.error_resolved_path_invalid_chars)))
    if (FORMAT_ALLOWED_PREFIXES.none { canonical.startsWith(it) }) return OpResult.failure(
        Exception(
            ctx.getString(
                R.string.error_image_must_be_user_storage,
                FORMAT_ALLOWED_PREFIXES.joinToString(", ")
            )
        )
    )
    val nioPath = Paths.get(canonical)
    if (!Files.exists(nioPath) || !Files.isRegularFile(nioPath)) return OpResult.failure(
        Exception(
            ctx.getString(R.string.error_image_not_regular_file)
        )
    )
    if (Files.isSymbolicLink(Paths.get(imagePath))) return OpResult.failure(
        Exception(ctx.getString(R.string.error_symlinks_not_allowed))
    )
    val imgArg = pathArg(canonical)
    val losetupCheck = RootShell.cmd(
        "losetup",
        ShellArg.literal("-a"),
        busyboxBin = busyboxBin,
        suppressErr = true,
        pipeInto = ShellCmd.of("grep", ShellArg.literal("-F"), imgArg)
    )
    if (losetupCheck.exitCode == 0 && losetupCheck.output.isNotBlank()) return OpResult.failure(
        Exception(ctx.getString(R.string.error_image_mounted_unmount_first))
    )
    val r = when (fsType.lowercase()) {
        "ext4" -> formatExt4(ctx, canonical, imgArg, busyboxBin)
        "exfat" -> formatExfat(ctx, canonical, imgArg, busyboxBin)
        else -> return OpResult.failure(
            Exception(
                ctx.getString(
                    R.string.error_unsupported_fs_type, fsType
                )
            )
        )
    }
    return if (r.exitCode == 0) OpResult.success(ctx.getString(R.string.alert_format_success, fsType))
    else OpResult.failure(Exception(ctx.getString(R.string.error_format_failed_output, r.output)))
}

private fun formatExt4(
    ctx: Context, canonical: String, imgArg: ShellArg, busyboxBin: String
): ShellResult {
    val mkfs = findBinary(busyboxBin, "mke2fs", "mkfs.ext4") ?: return ShellResult(
        -1, ctx.getString(R.string.error_no_mke2fs)
    )
    Log.d(TAG, "Formatting $canonical as ext4 with ${mkfs.path} (busybox=${mkfs.isBusyboxApplet})")
    return if (mkfs.isBusyboxApplet) {
        RootShell.cmd(
            "mkfs.ext4",
            ShellArg.literal("-t"),
            ShellArg.literal("ext4"),
            ShellArg.literal("-F"),
            imgArg,
            busyboxBin = busyboxBin,
            redirectErr = true
        )
    } else {
        RootShell.cmd(
            mkfs.path,
            ShellArg.literal("-t"),
            ShellArg.literal("ext4"),
            ShellArg.literal("-F"),
            imgArg,
            redirectErr = true
        )
    }
}

private fun formatExfat(
    ctx: Context, canonical: String, imgArg: ShellArg, busyboxBin: String
): ShellResult {
    val mkfs = findBinary(busyboxBin, "mkfs.exfat", "mkexfatfs") ?: return ShellResult(
        -1, ctx.getString(R.string.error_no_mkexfat)
    )
    Log.d(TAG, "Formatting $canonical as exfat with ${mkfs.path} (busybox=${mkfs.isBusyboxApplet})")
    return if (mkfs.isBusyboxApplet) {
        RootShell.cmd("mkfs.exfat", imgArg, busyboxBin = busyboxBin, redirectErr = true)
    } else {
        RootShell.cmd(mkfs.path, imgArg, redirectErr = true)
    }
}

private fun findBinary(busyboxBin: String, vararg names: String): MkfsLocation? {
    val candidates = names.flatMap { listOf("/system/bin/$it", it) }
    for (c in candidates) {
        val r = RootShell.cmd(
            c,
            ShellArg.literal("-V"),
            redirectErr = true,
            pipeInto = ShellCmd.of("head", ShellArg.literal("-1"))
        )
        if (r.exitCode == 0) return MkfsLocation(c, isBusyboxApplet = false)
    }
    val busyboxTest = names.firstOrNull { it.startsWith("mkfs.") } ?: names.firstOrNull()
    if (!busyboxTest.isNullOrEmpty() && busyboxBin.isNotEmpty()) {
        val r = RootShell.cmd(
            busyboxTest,
            ShellArg.literal("-V"),
            busyboxBin = busyboxBin,
            redirectErr = true,
            pipeInto = ShellCmd.of("head", ShellArg.literal("-1"))
        )
        if (r.exitCode == 0) return MkfsLocation(busyboxBin, isBusyboxApplet = true)
    }
    return null
}
