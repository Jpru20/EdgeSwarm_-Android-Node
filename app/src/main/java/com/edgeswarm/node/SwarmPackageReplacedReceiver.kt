package com.edgeswarm.node

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

// ANDROID_PACKAGE_REPLACE_RESUME_V1
class SwarmPackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent?
    ) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val nodeEnabled =
            context.getSharedPreferences(
                "EdgeSwarmNodeSettings",
                Context.MODE_PRIVATE
            ).getBoolean(
                "node_enabled",
                false
            )

        if (!nodeEnabled) {
            Log.i(
                "EdgeSwarm",
                "ANDROID_PACKAGE_REPLACE_RESUME_V1 " +
                    "nodeEnabled=false action=ignore"
            )
            return
        }

        val serviceIntent =
            Intent(
                context,
                SentinelService::class.java
            ).apply {
                action =
                    SentinelService.ACTION_PACKAGE_REPLACED
            }

        runCatching {
            context.startForegroundService(
                serviceIntent
            )
        }.onSuccess {
            Log.i(
                "EdgeSwarm",
                "ANDROID_PACKAGE_REPLACE_RESUME_V1 " +
                    "nodeEnabled=true action=start_service"
            )
        }.onFailure { error ->
            Log.e(
                "EdgeSwarm",
                "ANDROID_PACKAGE_REPLACE_RESUME_V1 " +
                    "start_failed=${error.message}",
                error
            )
        }
    }
}