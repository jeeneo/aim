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

data class ShellResult(val exitCode: Int, val output: String) {
    val isSuccess get() = exitCode == 0
}

class ShellArg private constructor(val quoted: String) {
    companion object {
        fun of(validated: String): ShellArg = ShellArg("'" + validated.replace("'", "'\\''") + "'")
        fun literal(s: String): ShellArg = ShellArg(s)
    }
}

internal val VALID_CHMOD_MODES = setOf("775", "664", "777")

fun pathArg(path: String): ShellArg {
    require(isValidPath(path)) { "Invalid path: $path" }
    return ShellArg.of(path)
}

fun numArg(n: Long): ShellArg = ShellArg.literal(n.toString())
fun numArg(n: Int): ShellArg = ShellArg.literal(n.toString())
fun enumArg(value: String, allowed: Set<String>): ShellArg {
    require(value in allowed) { "Value '$value' not in allowed set $allowed" }
    return ShellArg.of(value)
}

fun mountOptsArg(opts: String): ShellArg {
    require(opts.matches(Regex("^[a-zA-Z0-9_=,.:]+$"))) { "Invalid mount opts: $opts" }
    return ShellArg.of(opts)
}

fun secontextArg(ctx: String): ShellArg {
    require(ctx.matches(Regex("^[a-zA-Z0-9_:,.]+$")) && ':' in ctx) { "Invalid SELinux context: $ctx" }
    return ShellArg.of(ctx)
}

fun loopDevArg(dev: String): ShellArg {
    require(dev.length <= 32) { "Loop device path too long: ${dev.length}" }
    require(dev.matches(Regex("^/dev/(block/)?loop\\d+$"))) { "Invalid loop device: $dev" }
    return ShellArg.of(dev)
}

// binaries that are allowed to execute as root
private val ALLOWED_BINARIES = setOf(
    "mount", "umount", // mounting
    "losetup", // loop devices
    "mkdir", "rmdir", "mknod", "test", // filesystem and misc
    "chmod", "chown", "chcon", "find", // file attributes and searching
    "dd", "hexdump", "blkid", "stat", "wc", "grep", "awk", "ls", "head", "cat", "id", "command", "echo", // utilities
    "mke2fs", "mkfs.ext4", "mkfs.exfat", "mkexfatfs", // formatting
    "modprobe", "insmod", // probing/kernel
    "fuser", // process management
    "/system/bin/mke2fs", "/system/bin/mkfs.ext4", "/system/bin/mkfs.exfat", "/system/bin/mkexfatfs", // allow absolute paths for these
)

class ShellCmd private constructor(internal val fragment: String) {
    companion object {
        fun of(
            binary: String,
            vararg args: ShellArg,
            busyboxBin: String = "",
            stdinFrom: ShellArg? = null,
        ): ShellCmd {
            val resolved = resolveBinaryChecked(binary, busyboxBin)
            return ShellCmd(buildString {
                append(resolved)
                for (a in args) { append(' '); append(a.quoted) }
                if (stdinFrom != null) { append(" < "); append(stdinFrom.quoted) }
            })
        }
        fun chain(first: ShellCmd, vararg rest: ShellCmd): ShellCmd = ShellCmd((listOf(first) + rest).joinToString(" && ") { it.fragment })
        private fun resolveBinaryChecked(binary: String, busyboxBin: String): String {
            val baseName = binary.substringAfterLast('/')
            require(binary in ALLOWED_BINARIES || baseName in ALLOWED_BINARIES) {
                "Binary not allowed in command fragment: $binary"
            }
            return if (busyboxBin.isNotEmpty() && !binary.startsWith("/")) {
                "'" + busyboxBin.replace("'", "'\\''") + "' $binary"
            } else {
                if (binary.startsWith("/")) "'" + binary.replace("'", "'\\''") + "'"
                else binary
            }
        }
    }
}

object RootShell {
    private const val TAG = "RootShell"
    private const val MAX_OUTPUT_CHARS = 256_000 // ~256 KB guard against unbounded reads
    
    fun cmd(
        command: ShellCmd,
        pipeInto: ShellCmd? = null,
        chain: ShellCmd? = null,
        orChain: ShellCmd? = null,
        redirectErr: Boolean = false,
        ignoreError: Boolean = false,
        suppressErr: Boolean = false,
    ): ShellResult {
        val cmdLine = buildString {
            append(command.fragment)
            if (suppressErr) append(" 2>/dev/null")
            if (redirectErr) append(" 2>&1")
            if (pipeInto != null) { append(" | "); append(pipeInto.fragment) }
            if (chain != null) { append(" && "); append(chain.fragment) }
            if (orChain != null) { append(" || "); append(orChain.fragment) }
            if (ignoreError) append(" || true")
        }
        return exec(cmdLine)
    }
    fun cmd(
        binary: String,
        vararg args: ShellArg,
        busyboxBin: String = "",
        pipeInto: ShellCmd? = null,
        chain: ShellCmd? = null,
        orChain: ShellCmd? = null,
        redirectErr: Boolean = false,
        ignoreError: Boolean = false,
        suppressErr: Boolean = false,
    ): ShellResult {
        val command = try {
            ShellCmd.of(binary, *args, busyboxBin = busyboxBin)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Blocked execution of non-whitelisted binary: $binary")
            return ShellResult(-1, "Binary not allowed: $binary")
        }
        return cmd(command, pipeInto, chain, orChain, redirectErr, ignoreError, suppressErr)
    }

    internal fun testBusybox(candidateArg: ShellArg): Boolean {
        val result = exec("which busybox >/dev/null 2>&1")
        return result.isSuccess
    }

    internal fun exec(cmdLine: String): ShellResult = try {
        val pb = ProcessBuilder("su", "-c", cmdLine).redirectErrorStream(true)
        val p = pb.start()
        val output = p.inputStream.bufferedReader().use { reader ->
            val buf = CharArray(8192)
            val sb = StringBuilder()
            var n: Int
            while (reader.read(buf).also { n = it } != -1) {
                if (sb.length + n > MAX_OUTPUT_CHARS) {
                    sb.append(buf, 0, maxOf(0, MAX_OUTPUT_CHARS - sb.length))
                    while (reader.read(buf) != -1) { /* discard */ }
                    break
                }
                sb.append(buf, 0, n)
            }
            sb.toString()
        }.trim()
        ShellResult(p.waitFor(), output)
    } catch (e: Exception) {
        Log.e(TAG, "exec failed", e)
        ShellResult(-1, e.message ?: "Process failed")
    }
}

private val PATH_FORBIDDEN = charArrayOf(
    '\u0000', '\n', '\r',
    '`', '$', '|', ';', '&', '(', ')', '{', '}', '<', '>',
)

// unicode normalization (NFC/NFD) is intentionally not applied here,
// 1. all PATH_FORBIDDEN characters and ".." are ASCII; NFC cannot synthesize ASCII from non-ASCII
// 2. linux treats filenames as raw byte sequences without normalization
// 3. android filesystems (ext4, f2fs) do not perform Unicode normalization
fun isValidPath(path: String): Boolean {
    if (PATH_FORBIDDEN.any { it in path }) return false
    return path.split('/').none { it == ".." }
}

fun validatePath(path: String): Boolean = isValidPath(path)

private data class AllowedZone(val prefix: String, val minDepth: Int)

@SuppressLint("SdCardPath")
private val ALLOWED_ZONES = listOf(
    // only allow bind mounts on these locations "mount path", subdirs needed
    AllowedZone("/storage/emulated/", 2),   // /storage/emulated/{userId}/{subfolder}
    AllowedZone("/data/media/", 2),         // /data/media/{userId}/{subfolder}
    AllowedZone("/sdcard/", 1),             // /sdcard/{subfolder}
    AllowedZone("/mnt/media_rw/", 2),       // /mnt/media_rw/{deviceId}/{subfolder}
)

@SuppressLint("SdCardPath")
private val BLOCKED_STORAGE_ROOTS = listOf(
    "/data/media/0",
    "/storage/emulated/0",
    "/sdcard",
    "/mnt/media_rw",
)

fun validateBindDir(dir: String): String? {
    if (dir.isBlank()) return "Directory must not be empty"
    if (!dir.startsWith('/')) return "Directory must be an absolute path"
    if (!validatePath(dir)) return "Directory contains invalid characters or path traversal"
    val normalised = java.io.File(dir).normalize().path.trimEnd('/')
    if (normalised.isEmpty()) return "Directory must not be the root filesystem"
    val canonical = try {
        java.io.File(normalised).canonicalPath
    } catch (e: Exception) {
        return "Could not resolve canonical path: ${e.message}"
    }
    if (BLOCKED_STORAGE_ROOTS.any { canonical.equals(it, ignoreCase = false) || canonical == "$it/" }) {
        return "Bind directory must be a subfolder, not the storage root ($canonical)"
    }
    val zone = ALLOWED_ZONES.firstOrNull { canonical.startsWith(it.prefix) } ?: return "Bind directory must be under one of: ${ALLOWED_ZONES.joinToString(", ") { it.prefix }}"
    val tail = canonical.removePrefix(zone.prefix).trimEnd('/')
    val segments = if (tail.isEmpty()) emptyList() else tail.split('/')
    if (segments.size < zone.minDepth) {
        return "Bind directory must be a subfolder, not the root of the storage area"
    }
    if (segments.any { it == "Android" }) {
        return "Bind directory must not be inside the Android directory"
    }
    return null // valid
}

fun sanitizeStem(stem: String): String {
    val cleaned = stem.trimStart('.').ifBlank { "mounted_img" }
    return if (cleaned == ".." || cleaned == ".") "mounted_img" else cleaned
}
