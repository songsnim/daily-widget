package dev.daily.widget

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager

// Widget taps get no ripple feedback from RemoteViews; a tick confirms the tap, a heavier click rewards a done.
object Haptic {
    fun tap(ctx: Context, reward: Boolean) {
        val effect = if (reward) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_TICK
        ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            ?.vibrate(VibrationEffect.createPredefined(effect))
    }
}
