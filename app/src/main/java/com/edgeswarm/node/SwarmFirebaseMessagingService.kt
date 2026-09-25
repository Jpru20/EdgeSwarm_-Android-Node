package com.edgeswarm.node

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.Locale

// SWARM_ANDROID_FCM_CLIENT_V1
//
// FCM is the production wake channel for activated Android
// providers. Sentinel remains asleep while idle and wakes only
// for bounded registration/setup or eligible task execution.

internal object SwarmFcmState {
    private const val TAG = "SwarmFCM"

    const val PREFS = "SwarmFcmState"
    const val KEY_TOKEN = "fcm_token"
    const val KEY_TOKEN_UPDATED_AT = "fcm_token_updated_at"
    const val KEY_LAST_MESSAGE_AT = "fcm_last_message_at"
    const val KEY_LAST_MESSAGE_TYPE = "fcm_last_message_type"
    const val KEY_LAST_TASK_ID = "fcm_last_task_id"
    const val KEY_LAST_MESSAGE_ID = "fcm_last_message_id"

    fun persistToken(
        context: Context,
        token: String,
        source: String
    ) {
        val cleanToken = token.trim()

        if (cleanToken.isBlank()) {
            Log.w(TAG, "Ignoring empty FCM token.")
            return
        }

        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .putString(KEY_TOKEN, cleanToken)
            .putLong(
                KEY_TOKEN_UPDATED_AT,
                System.currentTimeMillis()
            )
            .apply()

        Log.i(
            TAG,
            "FCM token cached source=$source " +
                "length=${cleanToken.length}"
        )
    }

    fun cachedToken(context: Context): String? {
        return context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )
            .getString(KEY_TOKEN, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun cachedTokenUpdatedAt(context: Context): Long? {
        val value =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            ).getLong(
                KEY_TOKEN_UPDATED_AT,
                0L
            )

        return value.takeIf { it > 0L }
    }

    fun refreshCurrentToken(context: Context) {
        FirebaseMessaging.getInstance()
            .token
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w(
                        TAG,
                        "FCM token lookup failed.",
                        task.exception
                    )
                    return@addOnCompleteListener
                }

                val token =
                    task.result
                        ?.trim()
                        .orEmpty()

                if (token.isBlank()) {
                    Log.w(
                        TAG,
                        "FCM token lookup returned empty token."
                    )
                    return@addOnCompleteListener
                }

                persistToken(
                    context = context,
                    token = token,
                    source = "startup"
                )
            }
    }
}

class SwarmFirebaseMessagingService :
    FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)

        SwarmFcmState.persistToken(
            context = this,
            token = token,
            source = "onNewToken"
        )
    }

    override fun onMessageReceived(
        message: RemoteMessage
    ) {
        super.onMessageReceived(message)

        val type =
            message.data["type"]
                ?.trim()
                ?.uppercase(Locale.US)
                .orEmpty()

        val taskId =
            message.data["taskId"]
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: message.data["task_id"]
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)

        getSharedPreferences(
            SwarmFcmState.PREFS,
            MODE_PRIVATE
        ).edit()
            .putLong(
                SwarmFcmState.KEY_LAST_MESSAGE_AT,
                System.currentTimeMillis()
            )
            .putString(
                SwarmFcmState.KEY_LAST_MESSAGE_TYPE,
                type
            )
            .putString(
                SwarmFcmState.KEY_LAST_TASK_ID,
                taskId
            )
            .putString(
                SwarmFcmState.KEY_LAST_MESSAGE_ID,
                message.messageId
            )
            .apply()

        Log.i(
            "SwarmFCM",
            "FCM message received " +
                "type=${type.ifBlank { "UNSPECIFIED" }} " +
                "taskId=${taskId ?: "none"} " +
                "priority=${message.priority} " +
                "originalPriority=${message.originalPriority}"
        )

        if (type == "TASK_AVAILABLE") {
            Log.i(
                "SwarmFCM",
                "TASK_AVAILABLE wake signal verified."
            )

            val nodeEnabled =
                getSharedPreferences(
                    "EdgeSwarmNodeSettings",
                    MODE_PRIVATE
                ).getBoolean(
                    "node_enabled",
                    false
                )

            if (!nodeEnabled) {
                Log.i(
                    "SwarmFCM",
                    "TASK_AVAILABLE ignored because node_enabled=false."
                )
                return
            }

            if (
                message.priority !=
                    RemoteMessage.PRIORITY_HIGH
            ) {
                Log.w(
                    "SwarmFCM",
                    "TASK_AVAILABLE received without HIGH priority; " +
                        "foreground wake skipped."
                )
                return
            }

            val wakeIntent =
                Intent(
                    this,
                    SentinelService::class.java
                ).apply {
                    action =
                        SentinelService.ACTION_FCM_WAKE

                    taskId?.let {
                        putExtra(
                            SentinelService.EXTRA_FCM_TASK_ID,
                            it
                        )
                    }
                }

            try {
                ContextCompat.startForegroundService(
                    this,
                    wakeIntent
                )

                Log.i(
                    "SwarmFCM",
                    "TASK_AVAILABLE foreground wake requested " +
                        "taskId=${taskId ?: "none"}."
                )
            } catch (error: Exception) {
                Log.e(
                    "SwarmFCM",
                    "TASK_AVAILABLE foreground wake failed: " +
                        error.message,
                    error
                )
            }
        }
    }

    override fun onDeletedMessages() {
        super.onDeletedMessages()

        Log.w(
            "SwarmFCM",
            "FCM reported deleted pending messages."
        )
    }
}