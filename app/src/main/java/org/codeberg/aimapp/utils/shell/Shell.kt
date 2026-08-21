// SPDX-License-Identifier: GPL-3.0-or-later

package org.codeberg.aimapp.utils.shell

import android.util.Log
import com.topjohnwu.superuser.Shell

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
    "mount", "umount",
    // loop devices
    "losetup",
    // filesystem and misc
    "mkdir", "rmdir", "mknod",
    // file attributes and searching
    "chmod", "chown", "chcon", "find", "test",
    // utilities
    "busybox", // lol
    "blkid", "stat", "grep", "awk", "ls", "head", "cat", "id", "command", "echo",
    // formatting
    "mke2fs", "mkfs.ext4", "mkfs.exfat", "mkexfatfs",
    // probing/kernel
    "modprobe", "insmod",
    // process management
    "fuser"
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
        outputTo: String? = null,
    ): ShellResult {
        val cmdLine = buildString {
            append(command.fragment)
            if (ignoreError) append(" 2>/dev/null")
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
            if (outputTo != null) {
                append(" > "); append(outputTo)
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
        outputTo: String? = null,
    ): ShellResult {
        val command = try {
            ShellCmd.of(binary, *args, busyboxBin = busyboxBin)
        } catch (_: IllegalArgumentException) {
            Log.e(TAG, "Blocked execution of non-whitelisted binary: $binary")
            return ShellResult(-1, "Binary not allowed: $binary")
        }
        return cmd(
            command, pipeInto, chain, orChain, redirectErr, ignoreError, outputTo
        )
    }

    internal fun exec(cmdLine: String): ShellResult = try {
        val out = ArrayList<String>()
        val result = Shell.getShell().newJob().add(cmdLine).to(out, out).exec()
        var output = out.joinToString("\n").trim()
        if (output.length > MAX_OUTPUT_CHARS) output = output.take(MAX_OUTPUT_CHARS)
        ShellResult(result.code, output)
    } catch (e: Exception) {
        Log.e(TAG, "exec failed", e)
        ShellResult(-1, e.message ?: "Process failed")
    }
}
