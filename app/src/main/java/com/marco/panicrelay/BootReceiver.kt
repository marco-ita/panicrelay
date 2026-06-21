package com.marco.panicrelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms the guard service after a reboot, if it was enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs(ctx).serviceEnabled) {
            PanicService.start(ctx)
        }
    }
}
