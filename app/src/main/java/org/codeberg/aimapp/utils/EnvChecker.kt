// SPDX-License-Identifier: GPL-3.0-or-later

package org.codeberg.aimapp.utils

import android.os.Environment
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import org.codeberg.aimapp.AimApplication
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

sealed interface EnvCheckResult {
    data object RootDenied : EnvCheckResult
    data object BusyboxNotFound : EnvCheckResult
    data class Ready(val busyboxPath: String) : EnvCheckResult
}

val envStatus = MutableStateFlow(
    EnvironmentStatus(
        rootMessage = AimApplication.ctx.getString(R.string.env_not_checked),
        busyboxMessage = AimApplication.ctx.getString(R.string.env_not_checked),
        storageMessage = AimApplication.ctx.getString(R.string.env_not_checked),
    )
)

// image bytes are read in-process since dd/hexdump probes were removed,
// so shared-storage images need files access granted to the app itself
private fun checkStorageAccess(): Boolean = Environment.isExternalStorageManager()

private fun resolveBusyboxPath(): String? {
    BUSYBOX_CANDIDATES.forEach { candidate ->
        if (candidate.startsWith("/")) {
            if (RootShell.cmd("test", ShellArg.literal("-x"), pathArg(candidate)).exitCode == 0) {
                return candidate
            }
        } else {
            val resolved = RootShell.cmd(
                "command", ShellArg.literal("-v"), ShellArg.of(candidate), ignoreError = true
            ).output.lineSequence().firstOrNull()?.trim()
            if (!resolved.isNullOrBlank()) return resolved
        }
    }
    return null
}

// check root access and locate busybox
fun checkEnv(): EnvCheckResult {
    val rootOk = RootShell.cmd("id").let { it.exitCode == 0 && "uid=0" in it.output }
    if (!rootOk) return EnvCheckResult.RootDenied
    val busyboxPath = resolveBusyboxPath() ?: return EnvCheckResult.BusyboxNotFound
    val bbVersion =
        RootShell.cmd(busyboxPath).output.lineSequence()
            .firstOrNull()?.trim()
    Log.d("EnvChecker", "BusyBox: $bbVersion")
    Log.d("EnvChecker", "Android: ${android.os.Build.VERSION.RELEASE}")
    Log.d("EnvChecker", "All-files access: ${checkStorageAccess()}")
    return EnvCheckResult.Ready(busyboxPath)
}

fun checkEnvironment(): EnvironmentStatus {
    val storageOk = checkStorageAccess()
    val storageMessage = AimApplication.ctx.getString(
        if (storageOk) R.string.env_storage_granted else R.string.env_storage_denied
    )
    val status = when (val result = checkEnv()) {
        is EnvCheckResult.RootDenied -> EnvironmentStatus(
            rootMessage = AimApplication.ctx.getString(R.string.env_root_denied),
            busyboxMessage = AimApplication.ctx.getString(R.string.env_busybox_skipped),
            storageAvailable = storageOk,
            storageMessage = storageMessage,
        )

        is EnvCheckResult.BusyboxNotFound -> EnvironmentStatus(
            rootAvailable = true,
            rootMessage = AimApplication.ctx.getString(R.string.env_root_granted),
            busyboxMessage = AimApplication.ctx.getString(R.string.env_busybox_not_found),
            storageAvailable = storageOk,
            storageMessage = storageMessage,
            ready = false,
        )

        is EnvCheckResult.Ready -> EnvironmentStatus(
            rootAvailable = true,
            rootMessage = AimApplication.ctx.getString(R.string.env_root_granted),
            busyboxAvailable = true,
            busyboxPath = result.busyboxPath,
            busyboxMessage = AimApplication.ctx.getString(R.string.env_busybox_system_found),
            storageAvailable = storageOk,
            storageMessage = storageMessage,
            ready = true,
        )
    }
    envStatus.value = status
    return status
}
