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

import org.codeberg.dryerlint.aim.EnvironmentStatus

private val BUSYBOX_CANDIDATES = listOf(
    "busybox", "/system/bin/busybox", "/system/xbin/busybox",
    "/data/adb/magisk/busybox", "/data/adb/ksu/bin/busybox",
    "/data/adb/ap/bin/busybox",
)

// check root access and locate busybox, returns (status, busyboxPath)
fun checkEnv(currentBusybox: String): Pair<EnvironmentStatus, String> {
    val rootOk = RootShell.cmd("id").let { it.exitCode == 0 && "uid=0" in it.output }
    if (!rootOk) return EnvironmentStatus(
        rootMessage = "Root access denied", busyboxMessage = "Skipped (no root)"
    ) to ""
    val busyboxPath = BUSYBOX_CANDIDATES.firstOrNull { c ->
        val arg = if (c.startsWith("/")) pathArg(c) else ShellArg.of(c)
        RootShell.testBusybox(arg)
    }?.let { c ->
        if (c.startsWith("/")) c
        else RootShell.cmd(
            "command", ShellArg.literal("-v"), ShellArg.of(c), suppressErr = true
        ).output.lineSequence().firstOrNull()?.trim().takeIf { !it.isNullOrBlank() } ?: c
    }
    return if (busyboxPath != null) EnvironmentStatus(
        rootAvailable = true,
        rootMessage = "Root access granted",
        busyboxAvailable = true,
        busyboxPath = busyboxPath,
        busyboxMessage = "System busybox found",
        ready = true
    ) to busyboxPath
    else EnvironmentStatus(
        rootAvailable = true,
        rootMessage = "Root access granted",
        busyboxMessage = "No busybox found"
    ) to currentBusybox
}
