// SPDX-License-Identifier: GPL-3.0-or-later

package org.codeberg.aimapp.utils

import android.os.Environment
import android.util.Log
import com.topjohnwu.superuser.Shell
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
        val result = if (candidate.startsWith("/")) {
            RootShell.cmd("test", ShellArg.literal("-x"), pathArg(candidate))
        } else {
            RootShell.cmd(
                "command", ShellArg.literal("-v"), ShellArg.of(candidate), ignoreError = true
            )
        }
        Log.d(TAG, "busybox candidate '$candidate': code=${result.exitCode} out=${result.output}")
        if (candidate.startsWith("/")) {
            if (result.exitCode == 0) return candidate
        } else {
            val resolved = result.output.lineSequence().firstOrNull()?.trim()
            if (!resolved.isNullOrBlank()) return resolved
        }
    }
    return null
}

private const val TAG = "EnvChecker"

// check root access and locate busybox
fun checkEnv(): EnvCheckResult {
    Shell.getCachedShell()?.let { cached ->
        if (!cached.isRoot) runCatching { cached.close() }
    }
    val rootStatus = try {
        Shell.getShell().status
    } catch (_: Exception) {
        Shell.UNKNOWN
    }
    val rootOk = rootStatus == Shell.ROOT_SHELL
    val busyboxPath = if (rootOk) resolveBusyboxPath() else null
    val storageOk = checkStorageAccess()
    val missing = buildList {
        if (!rootOk) add("root unavailable (status=$rootStatus)")
        if (rootOk && busyboxPath == null) add("busybox not found (tried: $BUSYBOX_CANDIDATES)")
        if (!storageOk) add("all-files access not granted")
    }
    if (missing.isNotEmpty()) Log.w(TAG, "env not ready: ${missing.joinToString("; ")}")
    return when {
        !rootOk -> EnvCheckResult.RootDenied
        busyboxPath == null -> EnvCheckResult.BusyboxNotFound
        else -> EnvCheckResult.Ready(busyboxPath).also {
            val bbVersion =
                RootShell.cmd(busyboxPath).output.lineSequence()
                    .firstOrNull()?.trim()
            Log.d(TAG, "BusyBox: $bbVersion")
            Log.d(TAG, "Android: ${android.os.Build.VERSION.RELEASE}")
            Log.d(TAG, "All-files access: $storageOk")
        }
    }
}

fun checkEnvironment(): EnvironmentStatus {
    val storageOk = checkStorageAccess()
    val storageMessage = AimApplication.ctx.getString(
        if (storageOk) R.string.env_storage_granted else R.string.env_storage_denied
    )
    val status = try {
        when (val result = checkEnv()) {
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
    } catch (e: Exception) {
        Log.e(TAG, "Environment check failed", e)
        EnvironmentStatus(
            rootMessage = AimApplication.ctx.getString(R.string.alert_environment_check_failed)
        )
    }
    envStatus.value = status
    return status
}
