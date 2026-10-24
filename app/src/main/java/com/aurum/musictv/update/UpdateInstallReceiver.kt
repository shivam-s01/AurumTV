package com.aurum.musictv.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** Receives the PackageInstaller result. Not exported; only the system can reach it via our PendingIntent. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    UpdateManager.onAwaitingUser()
                } else {
                    UpdateManager.onInstallStatus(PackageInstaller.STATUS_FAILURE, null)
                }
            }
            else -> UpdateManager.onInstallStatus(
                status,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            )
        }
    }
}
