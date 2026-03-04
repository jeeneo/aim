package org.codeberg.dryerlint.aim

import android.app.Application

class AimApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.initialize()
    }
}
