package org.codeberg.aimapp

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import com.topjohnwu.superuser.Shell

class AimApplication : Application() {
    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var ctx: Context
    }

    override fun onCreate() {
        super.onCreate()
        ctx = this
        if (BuildConfig.DEBUG) Shell.enableVerboseLogging = true
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
        )
    }
}
