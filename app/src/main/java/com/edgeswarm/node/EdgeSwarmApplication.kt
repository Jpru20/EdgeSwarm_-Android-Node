package com.edgeswarm.node

import android.app.Application

class EdgeSwarmApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // SWARM_ANDROID_FCM_TOKEN_BOOTSTRAP_V1
        //
        // Cache the current FCM registration token on every
        // application start. Backend registration is added
        // after local FCM delivery has been verified.
        SwarmFcmState.refreshCurrentToken(this)
    }
}