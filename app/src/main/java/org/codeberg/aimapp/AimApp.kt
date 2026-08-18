package org.codeberg.aimapp

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context

class AimApplication : Application() {
    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var ctx: Context
    }
}
