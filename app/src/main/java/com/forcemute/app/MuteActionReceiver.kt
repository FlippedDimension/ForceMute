package com.forcemute.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class MuteActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("MuteActionReceiver", "onReceive action=$action")

        // Collapse notification shade first for instant visual feedback
        collapsePanel(context)

        when (action) {
            MuteService.ACTION_MUTE,
            MuteService.ACTION_MUTE_ALL -> {
                MuteService.isActive = true
            }
            MuteService.ACTION_UNMUTE -> {
                MuteService.isActive = false
            }
        }

        val svcIntent = Intent(context, MuteService::class.java).apply {
            this.action = action
        }
        context.startForegroundService(svcIntent)
    }

    private fun collapsePanel(context: Context) {
        try {
            val sbm = context.getSystemService("statusbar")
            if (sbm != null) {
                val method = sbm.javaClass.getMethod("collapsePanels")
                method.invoke(sbm)
                Log.d("MuteActionReceiver", "collapsePanels() succeeded")
            }
        } catch (e: Exception) {
            Log.w("MuteActionReceiver", "collapsePanels reflection failed, fallback", e)
            try {
                @Suppress("DEPRECATION")
                context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
            } catch (e2: Exception) {
                Log.w("MuteActionReceiver", "ACTION_CLOSE_SYSTEM_DIALOGS also failed", e2)
            }
        }
    }
}
