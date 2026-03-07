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

package org.codeberg.dryerlint.aim

import android.content.Context
import android.os.Process
import android.util.Log
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

object DebugLog {
    private const val TAG = "DebugLog"

    private val timberInitialized = AtomicBoolean(false)
    private val captureEnabled = AtomicBoolean(false)
    private var logcatProcess: java.lang.Process? = null
    private var logFile: File? = null

    private val rfc3339 = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH_mm_ss'Z'")
        .withZone(ZoneOffset.UTC)

    val isEnabled: Boolean get() = captureEnabled.get()

    val currentLogFile: File? get() = logFile

    fun initialize() {
        if (!timberInitialized.compareAndSet(false, true)) return
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(object : Timber.Tree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    Log.println(priority, tag ?: TAG, message)
                    t?.let { Log.println(priority, tag ?: TAG, Log.getStackTraceString(it)) }
                }
            })
        }
        Timber.tag(TAG).i("Timber initialized")
    }

    fun setEnabled(context: Context, enable: Boolean) {
        if (enable == captureEnabled.get()) return
        if (enable) {
            initialize()
            start(context)
        } else stop()
    }

    private fun start(context: Context) {
        val dir = context.getExternalFilesDir(null)
        if (dir == null) {
            Timber.tag(TAG).w("External files dir unavailable, cannot start logcat capture")
            return
        }
        dir.mkdirs()
        val filename = rfc3339.format(Instant.now()) + ".log"
        val file = File(dir, filename)
        logFile = file
        try {
            val pid = Process.myPid()
            val pb = ProcessBuilder(
                "logcat",
                "--pid=$pid",
                "-v", "threadtime",
                "-f", file.absolutePath,
            )
            pb.redirectErrorStream(true)
            logcatProcess = pb.start()
            captureEnabled.set(true)
            Timber.tag(TAG).i("Debug logcat capture started -> %s", file.absolutePath)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start logcat process")
            logFile = null
        }
    }

    private fun stop() {
        captureEnabled.set(false)
        logcatProcess?.destroy()
        logcatProcess = null
        Timber.tag(TAG).i("Debug logcat capture stopped")
        logFile = null
    }
}
