package com.astra.wakeup.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object AstraPanelLauncher {
    fun intent(context: Context): Intent = Intent(context, AstraOverlayActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    fun pendingIntent(context: Context, requestCode: Int = 7101): PendingIntent {
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
