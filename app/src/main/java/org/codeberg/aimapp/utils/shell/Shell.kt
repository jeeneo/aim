// SPDX-License-Identifier: GPL-3.0-or-later

package org.codeberg.aimapp.utils.shell

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
    // file attributes and searching
    "chmod",
    "chown",
    "chcon",
    "find",
    // utilities
    "busybox", // lol
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

    // will always run shells under global namespace
    // to avoid issues with su "permissions denied errors" that use inhereted namespaces
    private val SU_PREFIXES = listOf(
        listOf("su", "--mount-master", "-c"),
        listOf("su", "-mm", "-c"),
        listOf("su", "-c"),
    )

    @Volatile
    private var suPrefix: List<String>? = null

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
        } catch (_: IllegalArgumentException) {
            Log.e(TAG, "Blocked execution of non-whitelisted binary: $binary")
            return ShellResult(-1, "Binary not allowed: $binary")
        }
        return cmd(command, pipeInto, chain, orChain, redirectErr, ignoreError, suppressErr)
    }

    private fun startSuperuser(cmdLine: String): Process {
        suPrefix?.let { prefix ->
            return ProcessBuilder(*(prefix + cmdLine).toTypedArray()).redirectErrorStream(true)
                .start()
        }

        for (prefix in SU_PREFIXES) {
            val probe = runCatching {
                ProcessBuilder(*(prefix + "id").toTypedArray()).redirectErrorStream(true).start()
            }.getOrNull() ?: continue
            val probeOut = probe.inputStream.bufferedReader().use { it.readText() }
            val probeCode = probe.waitFor()
            if (probeCode == 0 && probeOut.contains("uid=0")) {
                suPrefix = prefix
                Log.i(TAG, "Resolved su prefix: ${prefix.joinToString(" ")}")
                return ProcessBuilder(*(prefix + cmdLine).toTypedArray()).redirectErrorStream(true)
                    .start()
            }
        }
        return ProcessBuilder("su", "-c", cmdLine).redirectErrorStream(true).start()
    }

    internal fun exec(cmdLine: String): ShellResult = try {
        fun execOnce(): ShellResult {
            val p = startSuperuser(cmdLine)
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
            return ShellResult(p.waitFor(), output)
        }

        val first = execOnce()
        if (suPrefix != null && first.exitCode != 0) {
            val out = first.output.lowercase()
            val suOptionError =
                (out.contains("unknown option") || out.contains("invalid option") || out.contains("unrecognized option")) && (out.contains(
                    "su"
                ) || out.contains("mount-master") || out.contains("-mm"))
            if (suOptionError) {
                suPrefix = null
                return execOnce()
            }
        }
        first
    } catch (e: Exception) {
        Log.e(TAG, "exec failed", e)
        ShellResult(-1, e.message ?: "Process failed")
    }
}
