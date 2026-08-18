package org.codeberg.aimapp.ui

import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.annotation.RequiresApi
import org.codeberg.aimapp.AimApplication

object HapticPatterns {
    private val context
        get() = AimApplication.ctx
    private val vibrator: Vibrator?
        get() = context.getSystemService(Vibrator::class.java)
    private const val TAP_DURATION_MS = 20L

    private fun vibratePredefined(effectId: Int) {
        val vibrator = vibrator ?: return
        val effect = VibrationEffect.createPredefined(effectId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(
                effect,
                VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_TOUCH).build()
            )
        } else {
            @Suppress("DEPRECATION") vibrator.vibrate(effect)
        }
    }

    private fun vibrateOldSdk() {
        val vibrator = vibrator ?: return
        @Suppress("DEPRECATION") vibrator.vibrate(TAP_DURATION_MS)
    }

    private fun vibrateWaveform(timings: LongArray, amplitudes: IntArray) {
        val vibrator = vibrator ?: return
        @Suppress("DEPRECATION") vibrator.vibrate(
            VibrationEffect.createWaveform(timings, amplitudes, -1)
        )
    }

    fun tap() {
        vibratePredefined(VibrationEffect.EFFECT_TICK)
    }

    fun longPress() {
        vibratePredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
    }

    fun tickLight() {
        vibratePredefined(VibrationEffect.EFFECT_CLICK)
    }

    fun tickMedium() = vibrateWaveform(longArrayOf(0L, 8L), intArrayOf(0, 240))
}
