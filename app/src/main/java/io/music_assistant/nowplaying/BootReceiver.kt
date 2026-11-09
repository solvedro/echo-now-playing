package io.music_assistant.nowplaying

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.music_assistant.nowplaying.service.OverlayService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        Log.i("BootReceiver", "Received boot broadcast action: $action")
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                val prefs = context.getSharedPreferences("ma_nowplaying_prefs", Context.MODE_PRIVATE)
                val autoOverlay = prefs.getBoolean("auto_overlay", true)
                if (autoOverlay) {
                    Log.i("BootReceiver", "Auto-starting OverlayService on boot/startup")
                    OverlayService.start(context)
                    OverlayService.showOverlay(context)
                }
            }
        }
    }
}
