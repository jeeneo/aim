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
import android.util.Log
import org.codeberg.aimapp.EnvironmentStatus
import org.codeberg.aimapp.R

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
        val bbVersion = RootShell.cmd(busyboxPath, ShellArg.literal("--version")).output.lineSequence().firstOrNull()?.trim()
        Log.d("EnvChecker", "BusyBox version: $bbVersion")
        val androidVersion = android.os.Build.VERSION.RELEASE
        Log.d("EnvChecker", "Android version: $androidVersion")
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
