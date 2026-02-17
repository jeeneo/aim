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
    val rootMsg = if (rootOk) "Root access granted" else "Root access denied"
    if (!rootOk) {
        return EnvironmentStatus(rootMessage = rootMsg, busyboxMessage = "Skipped (no root)") to ""
    }
    locateSystemBusybox()?.let { bb ->
        return EnvironmentStatus(rootAvailable = true, rootMessage = rootMsg, busyboxAvailable = true, busyboxPath = bb, busyboxMessage = "System busybox found", ready = true) to bb
    }
    return EnvironmentStatus(rootAvailable = true, rootMessage = rootMsg, busyboxMessage = "No busybox found") to currentBusybox
}

private fun locateSystemBusybox(): String? = BUSYBOX_CANDIDATES.firstOrNull { c ->
    val candidateArg = if (c.startsWith("/")) pathArg(c) else ShellArg.of(c)
    RootShell.testBusybox(candidateArg)
}?.let { c ->
    if (c.startsWith("/")) c
    else {
        val candidateArg = ShellArg.of(c)
        RootShell.cmd("command", ShellArg.literal("-v"), candidateArg, suppressErr = true, orChain = ShellCmd.of("echo", candidateArg)).output.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: c
    }
}
