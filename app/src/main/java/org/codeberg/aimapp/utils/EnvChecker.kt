// SPDX-License-Identifier: GPL-3.0-or-later

package org.codeberg.aimapp.utils

import android.content.Context
import android.util.Log
import org.codeberg.aimapp.R
import org.codeberg.aimapp.utils.mounts.EnvironmentStatus
import org.codeberg.aimapp.utils.shell.RootShell
import org.codeberg.aimapp.utils.shell.ShellArg
import org.codeberg.aimapp.utils.shell.pathArg

private val BUSYBOX_CANDIDATES = listOf(
    "busybox", "/system/bin/busybox", "/system/xbin/busybox",
    "/data/adb/magisk/busybox", "/data/adb/ksu/bin/busybox",
    "/data/adb/ap/bin/busybox",
)

private fun resolveBusyboxPath(): String? {
    BUSYBOX_CANDIDATES.forEach { candidate ->
        if (candidate.startsWith("/")) {
            if (RootShell.cmd("test", ShellArg.literal("-x"), pathArg(candidate)).exitCode == 0) {
                return candidate
            }
        } else {
            val resolved = RootShell.cmd(
                "command", ShellArg.literal("-v"), ShellArg.of(candidate), suppressErr = true
            ).output.lineSequence().firstOrNull()?.trim()
            if (!resolved.isNullOrBlank()) return resolved
        }
    }
    return null
}

// check root access and locate busybox, returns (status, busyboxPath)
fun checkEnv(ctx: Context): Pair<EnvironmentStatus, String> {
    val rootOk = RootShell.cmd("id").let { it.exitCode == 0 && "uid=0" in it.output }
    if (!rootOk) return EnvironmentStatus(
        rootMessage = ctx.getString(R.string.env_root_denied),
        busyboxMessage = ctx.getString(R.string.env_busybox_skipped),
    ) to ""
    val busyboxPath = resolveBusyboxPath()
    if (busyboxPath != null) {
        val bbVersion =
            RootShell.cmd(busyboxPath).output.lineSequence()
                .firstOrNull()?.trim()
        Log.d("EnvChecker", "BusyBox: $bbVersion")
        val androidVersion = android.os.Build.VERSION.RELEASE
        Log.d("EnvChecker", "Android: $androidVersion")
        return EnvironmentStatus(
            rootAvailable = true,
            rootMessage = ctx.getString(R.string.env_root_granted),
            busyboxAvailable = true,
            busyboxPath = busyboxPath,
            busyboxMessage = ctx.getString(R.string.env_busybox_system_found),
            ready = true
        ) to busyboxPath
    } else {
        return EnvironmentStatus(
            rootAvailable = true,
            rootMessage = ctx.getString(R.string.env_root_granted),
            busyboxMessage = ctx.getString(R.string.env_busybox_not_found),
            ready = false
        ) to ""
    }
}
