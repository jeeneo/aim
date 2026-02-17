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

import android.annotation.SuppressLint
import android.util.Log
import org.codeberg.dryerlint.aim.OpResult
import java.io.File
import java.nio.file.Files

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
fun formatImage(path: String, ready: Boolean, busyboxBin: String, fsType: String = "ext4"): OpResult {
    if (!ready) return OpResult.failure(Exception("Environment not ready"))
    val imagePath = path.trim()
    if (imagePath.isBlank()) return OpResult.failure(Exception("Empty path"))
    if (imagePath.endsWith(".iso", ignoreCase = true)) return OpResult.failure(Exception("ISO images are read-only and cannot be formatted"))
    if (!imagePath.endsWith(".img", ignoreCase = true)) return OpResult.failure(Exception("Only .img files supported"))
    if (!validatePath(imagePath)) return OpResult.failure(Exception("Path contains invalid characters"))
    val canonical = try {
        File(imagePath).canonicalPath
    } catch (e: Exception) {
        return OpResult.failure(Exception("Could not resolve image path: ${e.message}"))
    }
    if (!validatePath(canonical)) return OpResult.failure(Exception("Resolved path contains invalid characters"))
    if (!canonical.endsWith(".img", ignoreCase = true)) return OpResult.failure(Exception("Resolved path is not a .img file"))
    if (FORMAT_ALLOWED_PREFIXES.none { canonical.startsWith(it) }) return OpResult.failure(Exception("Image must be under user storage (${FORMAT_ALLOWED_PREFIXES.joinToString(", ")})"))
    val nioPath = java.nio.file.Paths.get(canonical)
    if (!Files.exists(nioPath)) return OpResult.failure(Exception("Image file does not exist"))
    if (!Files.isRegularFile(nioPath)) return OpResult.failure(Exception("Path is not a regular file"))
    if (Files.isSymbolicLink(java.nio.file.Paths.get(imagePath))) return OpResult.failure(Exception("Symbolic links are not allowed for format operations"))
    val imgArg = pathArg(canonical)
    // check not currently loop-attached
    val losetupCheck = RootShell.cmd("losetup", ShellArg.literal("-a"), busyboxBin = busyboxBin, suppressErr = true, pipeInto = ShellCmd.of("grep", ShellArg.literal("-F"), imgArg))
    if (losetupCheck.exitCode == 0 && losetupCheck.output.isNotBlank()) return OpResult.failure(Exception("Image is currently mounted - unmount first"))
    val r = when (fsType.lowercase()) {"ext4" -> formatExt4(canonical, imgArg, busyboxBin) "exfat" -> formatExfat(canonical, imgArg, busyboxBin) else -> return OpResult.failure(Exception("Unsupported filesystem type: $fsType")) }
    return if (r.exitCode == 0) OpResult.success("Formatted successfully as $fsType")
    else OpResult.failure(Exception("Format failed: ${r.output}"))
}

private fun formatExt4(canonical: String, imgArg: ShellArg, busyboxBin: String): ShellResult {
    val mkfs = mke2fsBinary(busyboxBin) ?: return ShellResult(-1, "No mke2fs or mkfs.ext4 found on device")
    Log.d(TAG, "Formatting $canonical as ext4 with ${mkfs.path} (busybox=${mkfs.isBusyboxApplet})")
    return if (mkfs.isBusyboxApplet) {
        RootShell.cmd("mkfs.ext4", ShellArg.literal("-t"), ShellArg.literal("ext4"), ShellArg.literal("-F"), imgArg, busyboxBin = busyboxBin, redirectErr = true)
    } else {
        RootShell.cmd(mkfs.path, ShellArg.literal("-t"), ShellArg.literal("ext4"), ShellArg.literal("-F"), imgArg, redirectErr = true)
    }
}

private fun formatExfat(canonical: String, imgArg: ShellArg, busyboxBin: String): ShellResult {
    val mkfs = mkExfatBinary(busyboxBin) ?: return ShellResult(-1, "No mkfs.exfat or mkexfatfs found on device")
    Log.d(TAG, "Formatting $canonical as exfat with ${mkfs.path} (busybox=${mkfs.isBusyboxApplet})")
    return if (mkfs.isBusyboxApplet) {
        RootShell.cmd("mkfs.exfat", imgArg, busyboxBin = busyboxBin, redirectErr = true)
    } else {
        RootShell.cmd(mkfs.path, imgArg, redirectErr = true)
    }
}

// locate mke2fs / mkfs.ext4 on the device
private fun mke2fsBinary(busyboxBin: String): MkfsLocation? {
    val candidates = listOf("mke2fs", "mkfs.ext4").flatMap { listOf("/system/bin/" + it, it) }
    for (c in candidates) {
        val r = RootShell.cmd(c, ShellArg.literal("-V"), redirectErr = true, pipeInto = ShellCmd.of("head", ShellArg.literal("-1")))
        if (r.exitCode == 0) return MkfsLocation(c, isBusyboxApplet = false)
    }
    if (busyboxBin.isNotEmpty()) {
        val r = RootShell.cmd("mkfs.ext4", ShellArg.literal("-V"), busyboxBin = busyboxBin, redirectErr = true, pipeInto = ShellCmd.of("head", ShellArg.literal("-1")))
        if (r.exitCode == 0) return MkfsLocation(busyboxBin, isBusyboxApplet = true)
    }
    return null
}

// locate mkfs.exfat / mkexfatfs on the device
private fun mkExfatBinary(busyboxBin: String): MkfsLocation? {
    val candidates = listOf("mkfs.exfat", "mkexfatfs").flatMap { listOf("/system/bin/" + it, it) }
    for (c in candidates) {
        val r = RootShell.cmd(c, ShellArg.literal("-V"), redirectErr = true, pipeInto = ShellCmd.of("head", ShellArg.literal("-1")))
        if (r.exitCode == 0) return MkfsLocation(c, isBusyboxApplet = false)
    }
    if (busyboxBin.isNotEmpty()) {
        val r = RootShell.cmd("mkfs.exfat", ShellArg.literal("-V"), busyboxBin = busyboxBin, redirectErr = true, pipeInto = ShellCmd.of("head", ShellArg.literal("-1")))
        if (r.exitCode == 0) return MkfsLocation(busyboxBin, isBusyboxApplet = true)
    }
    return null
}
