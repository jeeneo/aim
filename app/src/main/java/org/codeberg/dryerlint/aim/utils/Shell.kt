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

// binaries that are allowed to execute as root
private val ALLOWED_BINARIES = setOf(
    // mounting
    "mount",
    "umount",
    // loop devices
    "losetup",
    // filesystem and misc
    "mkdir",
    "rmdir",
    "mknod",
    "test",
    // file attributes and searching
    "chmod",
    "chown",
    "chcon",
    "find",
    // utilities
    "dd",
    "hexdump",
    "blkid",
    "stat",
    "wc",
    "grep",
    "awk",
    "ls",
    "head",
    "cat",
    "id",
    "command",
    "echo",
    // formatting
    "mke2fs",
    "mkfs.ext4",
    "mkfs.exfat",
    "mkexfatfs",
    // probing/kernel
    "modprobe",
    "insmod",
    // process management
    "fuser",
    // allow absolute paths for these
    "/system/bin/mke2fs",
    "/system/bin/mkfs.ext4",
    "/system/bin/mkfs.exfat",
    "/system/bin/mkexfatfs"
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
                for (a in args) {
                    append(' '); append(a.quoted)
                }
                if (stdinFrom != null) {
                    append(" < "); append(stdinFrom.quoted)
                }
            })
        }

        fun chain(first: ShellCmd, vararg rest: ShellCmd): ShellCmd =
            ShellCmd((listOf(first) + rest).joinToString(" && ") { it.fragment })

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
            if (pipeInto != null) {
                append(" | "); append(pipeInto.fragment)
            }
            if (chain != null) {
                append(" && "); append(chain.fragment)
            }
            if (orChain != null) {
                append(" || "); append(orChain.fragment)
            }
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
                    sb.appendRange(buf, 0, maxOf(0, MAX_OUTPUT_CHARS - sb.length))
                    while (reader.read(buf) != -1) { /* discard */
                    }
                    break
                }
                sb.appendRange(buf, 0, n)
            }
            sb.toString()
        }.trim()
        ShellResult(p.waitFor(), output)
    } catch (e: Exception) {
        Log.e(TAG, "exec failed", e)
        ShellResult(-1, e.message ?: "Process failed")
    }
}
