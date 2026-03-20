package org.codeberg.aimapp

import android.app.Application

class AimApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.initialize()
    }
}
