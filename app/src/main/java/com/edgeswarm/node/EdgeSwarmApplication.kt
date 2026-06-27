package com.edgeswarm.node

import android.app.Application

class EdgeSwarmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Globally disable GPU delegation to prevent driver crashes
    }
}

