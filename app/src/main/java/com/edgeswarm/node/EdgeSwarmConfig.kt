package com.edgeswarm.node

/**
 * Runtime configuration with safe production fallbacks.
 *
 * Custom BuildConfig fields remain supported when Gradle has generated them,
 * but the Android source no longer fails to compile or index before a Gradle sync.
 */
internal object EdgeSwarmConfig {
    private fun generatedString(name: String, fallback: String): String {
        val generated = runCatching {
            BuildConfig::class.java
                .getField(name)
                .get(null) as? String
        }.getOrNull()

        return generated
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: fallback
    }

    val apiBaseUrl: String = generatedString(
        "API_BASE_URL",
        "https://api.edgeswarm.io"
    ).trimEnd('/')

    val supabaseUrl: String = generatedString(
        "SUPABASE_URL",
        "https://xrmwmoqgukjztboemvgi.supabase.co"
    )

    val supabaseAnonKey: String = generatedString(
        "SUPABASE_ANON_KEY",
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InhybXdtb3FndWtqenRib2VtdmdpIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Nzk3MzgzNDcsImV4cCI6MjA5NTMxNDM0N30.3kP1uRFgRAgr2L2eh3Su36icRUHMEsfYIJc1RBV1jjM"
    )

    val releaseChannel: String = generatedString(
        "RELEASE_CHANNEL",
        "unified_private_beta"
    )

    const val unifiedProtocolVersion: String = "edgeswarm-unified-heartbeat-v1"
    const val packageType: String = "android_apk"
    const val publicReleaseSafe: Boolean = false
}
