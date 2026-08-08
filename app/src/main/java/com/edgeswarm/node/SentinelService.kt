package com.edgeswarm.node

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.annotation.SuppressLint
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import io.github.jan.supabase.auth.auth
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.round
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.crypto.Credentials
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric

class SentinelService : Service() {

    private fun attestationChallengeEndpoint(): String = "${gcpBaseUrl}/node/attestation/challenge"
    private fun attestationVerifyEndpoint(): String = "${gcpBaseUrl}/node/attestation/verify"
    private var lastAttestationAttemptMs: Long = 0L
    private val attestationIntervalMs: Long = 6L * 60L * 60L * 1000L

    private fun currentAttestationAppVersion(): String {
        val raw = appVersion.toString()
        return if (raw.startsWith("v")) raw else "v$raw"
    }


    companion object {
        const val ACTION_STOP_NODE =
            "com.edgeswarm.node.action.STOP_NODE"

        private const val NOTIFICATION_CHANNEL_ID =
            "sentinel_node"
        private const val NOTIFICATION_ID = 1001

        private const val NODE_SETTINGS_PREFS =
            "EdgeSwarmNodeSettings"
        private const val APP_PREFS =
            "EdgePrefs"
        private const val PREF_NODE_ENABLED =
            "node_enabled"

        private val mutableRunningState =
            MutableStateFlow(false)

        val runningState: StateFlow<Boolean> =
            mutableRunningState.asStateFlow()

        // ANDROID_LEVEL2_UI_STATE_FLOW_V1
        private val mutableLevel2StatusState =
            MutableStateFlow<String?>(null)

        val level2StatusState: StateFlow<String?> =
            mutableLevel2StatusState.asStateFlow()

        private fun publishLevel2Status(status: String?) {
            mutableLevel2StatusState.value = status
        }

        var isServiceRunning: Boolean
            get() = mutableRunningState.value
            private set(value) {
                mutableRunningState.value = value
            }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val appVersion = BuildConfig.VERSION_NAME
    private val appType = "android"
    // The execution loop is serial, so advertise the real capacity to the scheduler.
    private val androidConcurrencyLimit = 1

    // ANDROID_MOBILE_RESOURCE_GOVERNOR_V1
    // Reduce control-plane activity while the user is using the phone.
    private val idlePollIntervalMs = 5_000L
    private val interactivePollIntervalMs = 10_000L

    private val gcpBaseUrl = EdgeSwarmConfig.apiBaseUrl
    private val gcpUploadUrl = "$gcpBaseUrl/enterprise/submit-result"
    private val gcpJobsUrl = "$gcpBaseUrl/swarm/get-jobs"

    private val heartbeatUrl = "$gcpBaseUrl/admin/node-heartbeat"
    private val nodeStartedAtMs = System.currentTimeMillis()
    private val nodeStartedElapsedMs = SystemClock.elapsedRealtime()
    private var initialAccessToken: String? = null
    private var allowComputeTasks = true
    private var allowScrapingTasks = true
    private var allowBatteryTasks = true
    private var allowNeuralTasks = false
    private var level2Runtime: AndroidLevel2Runtime? = null

    @Volatile
    private var level2SelfTestPassed = false

    @Volatile
    private var level2ActiveModelId: String? = null

    @Volatile
    private var level2ActiveCapability: String? = null

    @Volatile
    private var level2ActiveBackend: String? = null

    @Volatile
    private var level2ActiveModelPath: String? = null

    @Volatile
    private var level2ActiveBackendType:
        AndroidLevel2Backend? = null

    @Volatile
    private var level2LastError: String? = null

    @Volatile
    private var lastThermalConstrained = false

    private var lastHeartbeatAtMs = 0L
    private val heartbeatIntervalMs = 15_000L
    private var nodeWalletAddress: String? = null
    private var heartbeatIdentityLogged = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    private fun setNodeEnabledPreference(
        enabled: Boolean
    ) {
        getSharedPreferences(
            NODE_SETTINGS_PREFS,
            MODE_PRIVATE
        ).edit()
            .putBoolean(
                PREF_NODE_ENABLED,
                enabled
            )
            .apply()
    }

    private fun createNodeNotificationChannel() {
        val notificationManager =
            getSystemService(
                NotificationManager::class.java
            )

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "EdgeSwarm Node",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description =
                "Shows when this device is available to the EdgeSwarm network."
            setShowBadge(false)
        }

        notificationManager.createNotificationChannel(
            channel
        )
    }

    private fun buildNodeNotification(
        statusText: String
    ): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(
                this,
                MainActivity::class.java
            ).apply {
                flags =
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val stopNodeIntent = PendingIntent.getService(
            this,
            1,
            Intent(
                this,
                SentinelService::class.java
            ).apply {
                action = ACTION_STOP_NODE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(
            this,
            NOTIFICATION_CHANNEL_ID
        )
            .setContentTitle("EdgeSwarm Node")
            .setContentText(statusText)
            .setSmallIcon(
                android.R.drawable.ic_dialog_info
            )
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(
                Notification.CATEGORY_SERVICE
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(
                        this,
                        android.R.drawable
                            .ic_menu_close_clear_cancel
                    ),
                    "Deactivate",
                    stopNodeIntent
                ).build()
            )
            .build()
    }

    private fun startNodeForeground(
        notification: Notification
    ) {
        if (
            Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun updateNodeNotification(
        statusText: String
    ) {
        getSystemService(
            NotificationManager::class.java
        ).notify(
            NOTIFICATION_ID,
            buildNodeNotification(statusText)
        )
    }

    private fun isThermallyConstrained(): Boolean {
        if (
            Build.VERSION.SDK_INT <
                Build.VERSION_CODES.Q
        ) {
            return false
        }

        val powerManager =
            getSystemService(
                POWER_SERVICE
            ) as PowerManager

        return powerManager.currentThermalStatus >=
            PowerManager.THERMAL_STATUS_MODERATE
    }

    private fun currentPollIntervalMs(): Long {
        val powerManager =
            getSystemService(
                POWER_SERVICE
            ) as PowerManager

        return if (powerManager.isInteractive) {
            interactivePollIntervalMs
        } else {
            idlePollIntervalMs
        }
    }

    private fun currentIdleNotificationText(): String {
        return when {
            allowNeuralTasks &&
                level2SelfTestPassed &&
                isThermallyConstrained() ->
                "Level 2 paused while the device cools"

            isLevel2Ready() ->
                "Level 2 - Gemma 4 E2B - " +
                    (
                        level2ActiveBackend
                            ?.uppercase()
                            ?: "READY"
                    )

            allowNeuralTasks &&
                level2LastError == null ->
                "Preparing Level 2 - Gemma 4 E2B"

            allowNeuralTasks ->
                "Level 1 active - Level 2 unavailable"

            else ->
                "Level 1 deterministic node is active"
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (intent?.action == ACTION_STOP_NODE) {
            Log.d(
                "EdgeSwarm",
                "Node stop requested from notification."
            )
            setNodeEnabledPreference(false)
            isServiceRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (isServiceRunning) {
            return START_STICKY
        }

        createNodeNotificationChannel()

        startNodeForeground(
            buildNodeNotification(
                "Node is starting..."
            )
        )

        val nodeSettings =
            getSharedPreferences(
                NODE_SETTINGS_PREFS,
                MODE_PRIVATE
            )

        val appPreferences =
            getSharedPreferences(
                APP_PREFS,
                MODE_PRIVATE
            )

        val savedNodeEnabled =
            nodeSettings.getBoolean(
                PREF_NODE_ENABLED,
                false
            )

        if (
            intent == null &&
            !savedNodeEnabled
        ) {
            Log.i(
                "EdgeSwarm",
                "Sticky restart ignored because the node was not user-enabled."
            )
            stopSelf()
            return START_NOT_STICKY
        }

        val userEmail =
            intent
                ?.getStringExtra("USER_EMAIL")
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: appPreferences
                    .getString(
                        "auth_email",
                        null
                    )
                    ?.trim()
                    .orEmpty()

        val suppliedAccessToken =
            intent
                ?.getStringExtra("ACCESS_TOKEN")
                ?.trim()
                .orEmpty()

        val restoredSessionToken =
            runCatching {
                supabase.auth
                    .currentSessionOrNull()
                    ?.accessToken
            }
                .getOrNull()
                ?.trim()
                .orEmpty()

        val resolvedAccessToken =
            suppliedAccessToken.ifBlank {
                restoredSessionToken
            }

        if (
            userEmail.isBlank() ||
            resolvedAccessToken.isBlank()
        ) {
            Log.e(
                "EdgeSwarm",
                "Node activation rejected: authenticated provider session is missing."
            )
            setNodeEnabledPreference(false)
            stopSelf()
            return START_NOT_STICKY
        }

        initialAccessToken =
            resolvedAccessToken

        allowComputeTasks =
            if (
                intent?.hasExtra(
                    "ALLOW_COMPUTE"
                ) == true
            ) {
                intent.getBooleanExtra(
                    "ALLOW_COMPUTE",
                    true
                )
            } else {
                nodeSettings.getBoolean(
                    "allow_compute",
                    true
                )
            }

        allowScrapingTasks =
            if (
                intent?.hasExtra(
                    "ALLOW_SCRAPING"
                ) == true
            ) {
                intent.getBooleanExtra(
                    "ALLOW_SCRAPING",
                    true
                )
            } else {
                nodeSettings.getBoolean(
                    "allow_scraping",
                    true
                )
            }

        allowBatteryTasks =
            if (
                intent?.hasExtra(
                    "ALLOW_BATTERY_TASKS"
                ) == true
            ) {
                intent.getBooleanExtra(
                    "ALLOW_BATTERY_TASKS",
                    true
                )
            } else {
                nodeSettings.getBoolean(
                    "allow_battery_tasks",
                    true
                )
            }

        allowNeuralTasks =
            if (
                intent?.hasExtra(
                    "ALLOW_NEURAL"
                ) == true
            ) {
                intent.getBooleanExtra(
                    "ALLOW_NEURAL",
                    false
                )
            } else {
                nodeSettings.getBoolean(
                    "allow_neural",
                    false
                )
            }

        setNodeEnabledPreference(true)

        if (allowNeuralTasks && level2Runtime == null) {
            level2Runtime = AndroidLevel2Runtime(
                cacheDir = filesDir.resolve("level2_litert_lm_cache")
            )
            Log.i(
                "EdgeSwarm",
                "Level 2 enabled; waiting for a verified model installation."
            )
        }

        isServiceRunning = true
        acquireExecutionWakeLock()

        updateNodeNotification(
            currentIdleNotificationText()
        )

        startLevel2SelfTestIfEnabled()
        startHeadlessEngine(userEmail)

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(
            "EdgeSwarm",
            "Node deactivation requested. Stopping service."
        )
        isServiceRunning = false
        serviceScope.cancel()
        initialAccessToken = null
        runCatching {
            level2Runtime?.close()
        }
        level2Runtime = null
        allowNeuralTasks = false
        level2SelfTestPassed = false
        level2ActiveModelId = null
        level2ActiveCapability = null
        level2ActiveBackend = null
        level2ActiveModelPath = null
        level2ActiveBackendType = null
        level2LastError = null
        publishLevel2Status(null)
        releaseExecutionWakeLock()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(
            "EdgeSwarm",
            "Android foreground-service quota reached. " +
                "Stopping the node cleanly."
        )
        isServiceRunning = false
        stopSelf(startId)
    }

    private fun acquireExecutionWakeLock() {
        releaseExecutionWakeLock()

        val powerManager =
            getSystemService(POWER_SERVICE) as PowerManager

        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EdgeSwarm::NodeWakeLock"
        ).apply {
            acquire()
        }
    }

    private fun releaseExecutionWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                runCatching { lock.release() }
            }
        }
        wakeLock = null
    }

    private fun getBatteryInfoForHeartbeat(): Pair<Boolean?, Int?> {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

        val pct = if (level >= 0 && scale > 0) {
            ((level.toFloat() / scale.toFloat()) * 100).toInt()
        } else {
            null
        }

        return Pair(isCharging, pct)
    }

    private fun getBatteryTempCForHeartbeat(): Float? {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempTenthsC = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE

        return if (tempTenthsC == Int.MIN_VALUE || tempTenthsC <= 0) {
            null
        } else {
            tempTenthsC / 10.0f
        }
    }

    @SuppressLint("HardwareIds")
    private fun legacyCompatibleHardwareId(): String {
        val deviceName = "${Build.MANUFACTURER}_${Build.MODEL}"
            .replace(" ", "")

        val androidId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()

        // Preserve the existing Android node identity format so current
        // attestations and provider-node links do not silently fork.
        val suffix = androidId.take(6).ifBlank { "local" }
        return "${deviceName}_${suffix}"
    }

    private fun currentAccessToken(): String? {
        val sessionToken = runCatching {
            supabase.auth.currentSessionOrNull()?.accessToken
        }.getOrNull()?.takeIf { it.isNotBlank() }

        if (sessionToken != null) {
            initialAccessToken = sessionToken
            return sessionToken
        }

        return initialAccessToken?.takeIf { it.isNotBlank() }
    }

    private fun refreshAccessTokenAfterUnauthorized(): String? {
        return try {
            runBlocking {
                supabase.auth.refreshCurrentSession()
            }
            supabase.auth.currentSessionOrNull()?.accessToken
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.also { initialAccessToken = it }
        } catch (e: Exception) {
            Log.e("EdgeSwarm", "Supabase session refresh failed after 401: " + e.message)
            initialAccessToken = null
            null
        }
    }

    private fun authenticatedRequestBuilder(url: String): Request.Builder {
        val token = currentAccessToken()
            ?: throw IllegalStateException("Authenticated Supabase session is unavailable.")

        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("X-EdgeSwarm-Node-Type", "android")
    }

    private fun logTiming(marker: String, taskId: Int? = null, elapsedMs: Long? = null) {
        val taskPart = taskId?.let { " taskId=$it" }.orEmpty()
        val elapsedPart = elapsedMs?.let { " elapsedMs=$it" }.orEmpty()
        Log.i("EdgeSwarmTiming", "ANDROID_NODE_TIMING_V1 marker=$marker$taskPart$elapsedPart")
    }

    private fun handleTerminalControlPlaneFailure(
        responseCode: Int,
        operation: String
    ) {
        when (responseCode) {
            401 -> {
                Log.e(
                    "EdgeSwarm",
                    "$operation rejected the provider session. " +
                        "Stopping node for re-authentication."
                )
                initialAccessToken = null
                isServiceRunning = false
                stopSelf()
            }

            426 -> {
                Log.e(
                    "EdgeSwarm",
                    "$operation requires a newer Android node release. " +
                        "Stopping this version."
                )
                isServiceRunning = false
                stopSelf()
            }
        }
    }

    private val packageSha256: String by lazy { sha256File(applicationInfo.sourceDir) }
    private val signingCertificateSha256: String by lazy { computeSigningCertificateSha256() }

    private fun sha256File(path: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(path).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w("EdgeSwarm", "Could not hash Android package: ${e.message}")
            "unavailable"
        }
    }

    @Suppress("DEPRECATION")
    private fun computeSigningCertificateSha256(): String {
        return try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }

            val packageInfo = packageManager.getPackageInfo(packageName, flags)
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                packageInfo.signatures
            }

            val certificate = signatures?.firstOrNull()?.toByteArray()
                ?: return "unavailable"

            MessageDigest.getInstance("SHA-256")
                .digest(certificate)
                .joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w("EdgeSwarm", "Could not hash Android signing certificate: ${e.message}")
            "unavailable"
        }
    }

    private fun sendNodeHeartbeat(
        hardwareId: String,
        providerEmail: String,
        currentTaskIds: List<Int> = emptyList()
    ) {
        try {
            val heartbeatWalletAddress = getNodeCredentials(providerEmail).address
            nodeWalletAddress = heartbeatWalletAddress

            val batteryInfo = getBatteryInfoForHeartbeat()
            val batteryTempC = getBatteryTempCForHeartbeat()
            val level2Ready = isLevel2Ready()
            val capabilitiesList = getAndroidCapabilities()
            val capabilities = JSONArray().apply {
                capabilitiesList.forEach { put(it) }
            }
            val eligibleModelCapabilities = JSONArray().apply {
                if (level2Ready) {
                    put(
                        level2ActiveCapability
                            ?: "Neural-Inference-3B"
                    )
                }
            }

            val level2ModelStatus = when {
                level2Ready -> "ready"
                !allowNeuralTasks -> "not_required"
                level2LastError != null -> "error"
                else -> "self_test_pending"
            }

            val level2ModelCapability =
                if (level2Ready) {
                    level2ActiveCapability
                        ?: "Neural-Inference-3B"
                } else {
                    null
                }

            val level2ModelId =
                if (level2Ready) {
                    level2ActiveModelId
                        ?: "gemma4:e2b"
                } else {
                    "none"
                }

            val currentTasks = JSONArray().apply {
                currentTaskIds.forEach { put(it) }
            }

            val payload = JSONObject()
                .put("hardwareId", hardwareId)
                .put("worker", heartbeatWalletAddress)
                .put("providerEmail", providerEmail)
                .put("nodeType", "android")
                .put("platform", "Android")
                .put("appVersion", currentAttestationAppVersion())
                .put("appType", appType)
                .put("packageName", packageName)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("releaseChannel", EdgeSwarmConfig.releaseChannel)
                .put("architecture", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
                .put("osVersion", Build.VERSION.RELEASE ?: "unknown")
                .put("sdkInt", Build.VERSION.SDK_INT)
                .put("manufacturer", Build.MANUFACTURER ?: "unknown")
                .put("deviceModel", Build.MODEL ?: "unknown")
                .put("packageSha256", packageSha256)
                .put("runtimeSha256", packageSha256)
                .put("signingCertificateSha256", signingCertificateSha256)
                .put("signatureType", "android_signing_certificate")
                .put("authenticationMode", "supabase_bearer")
                .put("capabilities", capabilities)
                .put("eligibleModelCapabilities", eligibleModelCapabilities)
                .put(
                    "recommendedModelCapability",
                    level2ModelCapability ?: JSONObject.NULL
                )
                .put("modelStatus", level2ModelStatus)
                .put(
                    "modelCapability",
                    level2ModelCapability ?: JSONObject.NULL
                )
                .put("modelId", level2ModelId)
                .put(
                    "edgeLevel",
                    if (level2Ready) 2 else 1
                )
                .put(
                    "edgeLevelLabel",
                    if (level2Ready) "Level 2" else "Level 1"
                )
                .put(
                    "edge_level",
                    if (level2Ready) 2 else 1
                )
                .put(
                    "edge_level_label",
                    if (level2Ready) "Level 2" else "Level 1"
                )
                .put(
                    "runtime",
                    if (level2Ready) {
                        "litert-lm"
                    } else {
                        "android-kotlin-deterministic-v2"
                    }
                )
                .put(
                    "runtimeAcceleration",
                    if (level2Ready) {
                        level2ActiveBackend ?: "unknown"
                    } else {
                        "cpu"
                    }
                )
                .put("canReceivePaidJobs", capabilitiesList.isNotEmpty())
                .put(
                    "canReceiveNeuralJobs",
                    level2Ready
                )
                .put("status", "online")
                .put("startedAt", java.time.Instant.ofEpochMilli(nodeStartedAtMs).toString())
                .put("uptimeSec", ((SystemClock.elapsedRealtime() - nodeStartedElapsedMs) / 1000L).toInt())
                .put("currentTaskIds", currentTasks)
                .put("concurrencyLimit", androidConcurrencyLimit)
                .put("isCharging", batteryInfo.first ?: JSONObject.NULL)
                .put("batteryPct", batteryInfo.second ?: JSONObject.NULL)
                .put("batteryTempC", batteryTempC ?: JSONObject.NULL)
                .put("playIntegritySupported", true)
                .put("playIntegrityMode", "classic_nonce_v1")
                .put("debugBuild", BuildConfig.DEBUG)

            if (!heartbeatIdentityLogged) {
                Log.d(
                    "EdgeSwarm",
                    "ANDROID_HEARTBEAT_IDENTITY_V3 ${payload}"
                )
                heartbeatIdentityLogged = true
            } else {
                Log.d(
                    "EdgeSwarm",
                    "Heartbeat prepared -> hardwareId=$hardwareId " +
                        "tasks=${currentTaskIds.size} " +
                        "battery=${batteryInfo.second ?: -1}% " +
                        "modelStatus=$level2ModelStatus " +
                        "modelId=$level2ModelId " +
                        "modelCapability=${level2ModelCapability ?: "none"} " +
                        "edgeLevel=${if (level2Ready) 2 else 1} " +
                        "acceleration=${if (level2Ready) {
                            level2ActiveBackend ?: "unknown"
                        } else {
                            "cpu"
                        }} " +
                        "neural=$level2Ready"
                )
            }

            val body = payload
                .toString()
                .toRequestBody("application/json".toMediaType())
            var request = authenticatedRequestBuilder(heartbeatUrl)
                .post(body)
                .build()
            var authRefreshUsed = false

            while (true) {
                var retryAfterRefresh = false
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()

                    if (response.isSuccessful) {
                        Log.d("EdgeSwarm", "Heartbeat sent.")
                        return
                    }

                    if (response.code == 401 && !authRefreshUsed) {
                        authRefreshUsed = true
                        if (refreshAccessTokenAfterUnauthorized() != null) {
                            request = authenticatedRequestBuilder(heartbeatUrl)
                                .post(body)
                                .build()
                            retryAfterRefresh = true
                            Log.w("EdgeSwarm", "Heartbeat bearer refreshed; retrying once.")
                        }
                    }

                    if (!retryAfterRefresh) {
                        handleTerminalControlPlaneFailure(response.code, "Heartbeat")
                        Log.w("EdgeSwarm", "Heartbeat failed: HTTP " + response.code + " - " + responseBody)
                    }
                }

                if (!retryAfterRefresh) return
            }
        } catch (e: Exception) {
            Log.w("EdgeSwarm", "Heartbeat error: ${e.message}")
        }
    }

    private fun maybeSendNodeHeartbeat(
        hardwareId: String,
        providerEmail: String,
        currentTaskIds: List<Int> = emptyList(),
        force: Boolean = false
    ) {
        val now = System.currentTimeMillis()

        if (!force && now - lastHeartbeatAtMs < heartbeatIntervalMs) {
            return
        }

        lastHeartbeatAtMs = now
        sendNodeHeartbeat(hardwareId, providerEmail, currentTaskIds)
        performNodeAttestationPhase1(hardwareId, providerEmail)
    }

    private fun startHeadlessEngine(userEmail: String) {
        serviceScope.launch {
            runCatching {
                android.os.Process.setThreadPriority(
                    android.os.Process
                        .THREAD_PRIORITY_BACKGROUND
                )
            }

            try {
                Log.d(
                    "EdgeSwarm",
                    "Headless Engine Booting in mobile-balanced mode."
                )

                if (currentAccessToken().isNullOrBlank()) {
                    throw IllegalStateException("Authenticated provider session is unavailable.")
                }

                val hardwareId = legacyCompatibleHardwareId()

                try {
                    nodeWalletAddress = getNodeCredentials(userEmail).address
                    performNodeAttestationPhase1(hardwareId, userEmail, force = true)
                    Log.d("EdgeSwarm", "Android wallet bound: ${nodeWalletAddress?.take(10)}...")
                } catch (e: Exception) {
                    Log.w("EdgeSwarm", "Wallet bind for heartbeat failed: ${e.message}")
                }

                Log.d(
                    "EdgeSwarm",
                    "Android engine started. Level 2 activates after verified self-test."
                )
                maybeSendNodeHeartbeat(hardwareId, userEmail, force = true)

                while (isServiceRunning) {
                    val thermalConstrained =
                        isThermallyConstrained()

                    if (
                        thermalConstrained !=
                            lastThermalConstrained
                    ) {
                        lastThermalConstrained =
                            thermalConstrained

                        updateNodeNotification(
                            currentIdleNotificationText()
                        )

                        maybeSendNodeHeartbeat(
                            hardwareId,
                            userEmail,
                            force = true
                        )
                    }

                    maybeSendNodeHeartbeat(
                        hardwareId,
                        userEmail
                    )

                    val batteryInfo = getBatteryInfoForHeartbeat()
                    val isCharging = batteryInfo.first == true

                    if (!allowBatteryTasks && !isCharging) {
                        Log.d("EdgeSwarm", "Waiting for charging state because battery task mode is disabled.")
                        delay(currentPollIntervalMs())
                        continue
                    }

                    val task = fetchTaskFromMempool(
                        hardwareId,
                        userEmail
                    )

                    if (!isServiceRunning) {
                        break
                    }

                    if (task != null) {
                        val taskId = task.getInt("taskId")
                        val prompt = task.getString("prompt")
                        // ANDROID_CAPABILITY_BASED_TASK_ROUTING_V1
                        // Route by the backend capability/model contract, not only prompt prefixes.
                        val requiredModel = task
                            .optString("requiredModel", task.optString("required_model", ""))
                            .trim()

                        val requiredCapability = task
                            .optString(
                                "requiredCapability",
                                task.optString("required_capability", "")
                            )
                            .trim()

                        val selectedModel = task
                            .optString("selectedModel", task.optString("selected_model", ""))
                            .trim()

                        val routeValues = listOf(
                            requiredModel,
                            requiredCapability,
                            selectedModel
                        )

                        val isExactExtractionTask =
                            prompt.startsWith(
                                "prompt://EXACT_EXTRACTION_PLAN_V1:",
                                ignoreCase = true
                            ) ||
                            routeValues.any {
                                it.equals("Exact-Extraction", ignoreCase = true)
                            } ||
                            selectedModel.contains("exact", ignoreCase = true)

                        val isComputeTask =
                            prompt.startsWith("compute://") ||
                            routeValues.any {
                                it.equals("Distributed-Compute", ignoreCase = true)
                            } ||
                            selectedModel.contains(
                                "deterministic-compute",
                                ignoreCase = true
                            )

                        val isScrapeTask =
                            prompt.startsWith("http://") ||
                            prompt.startsWith("https://") ||
                            routeValues.any {
                                it.equals("Data-Scraper", ignoreCase = true)
                            } ||
                            selectedModel.contains("scrape", ignoreCase = true)

                        val isNeuralTask = routeValues.any {
                            it.startsWith(
                                "Neural-Inference",
                                ignoreCase = true
                            )
                        }

                        Log.d(
                            "EdgeSwarm",
                            "Task routing -> taskId=$taskId " +
                                "requiredModel=$requiredModel " +
                                "requiredCapability=$requiredCapability " +
                                "selectedModel=$selectedModel"
                        )

                        maybeSendNodeHeartbeat(hardwareId, userEmail, listOf(taskId), force = true)

                        logTiming("task_returned_to_loop", taskId)
                        Log.d(
                            "EdgeSwarm",
                            "Task received -> taskId=$taskId"
                        )

                        updateNodeNotification(
                            if (isNeuralTask) {
                                "Running Level 2 inference..."
                            } else {
                                "Processing EdgeSwarm task..."
                            }
                        )

                        var aiOutput = ""
                        var isError = false
                        val start = SystemClock.elapsedRealtime()
                        logTiming("execution_start", taskId)

                        try {
                            if (isComputeTask && allowComputeTasks) {
                                Log.d("EdgeSwarm", "Executing deterministic tensor math...")
                                val checkpointIndices = task.optJSONArray("checkpoint_indices") ?: task.optJSONArray("checkpointIndices")
                                Log.d("EdgeSwarm", "Task checkpoint indices: $checkpointIndices")
                                aiOutput = runDeterministicCompute(prompt, checkpointIndices)

                            } else if (
                                isScrapeTask &&
                                allowScrapingTasks
                            ) {
                                Log.d(
                                    "EdgeSwarm",
                                    "Executing deterministic data scrape..."
                                )

                                val scrapeUrl =
                                    extractFirstHttpUrl(prompt)
                                        ?: throw IllegalArgumentException(
                                            "Data-Scraper task did not contain an HTTPS URL."
                                        )

                                val request = Request.Builder()
                                    .url(scrapeUrl)
                                    .header(
                                        "User-Agent",
                                        "Mozilla/5.0 (Linux; Android " +
                                            "${Build.VERSION.RELEASE}; ${Build.MODEL}) " +
                                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                            "Chrome/150.0.0.0 Mobile Safari/537.36 " +
                                            "EdgeSwarm/${BuildConfig.VERSION_NAME}"
                                    )
                                    .header(
                                        "Accept",
                                        "text/html,application/xhtml+xml," +
                                            "application/xml;q=0.9,*/*;q=0.8"
                                    )
                                    .header(
                                        "Accept-Language",
                                        "en-US,en;q=0.7"
                                    )
                                    .build()

                                httpClient.newCall(request).execute().use {
                                    response ->
                                    if (!response.isSuccessful) {
                                        throw IOException(
                                            "Scrape failed: HTTP ${response.code}"
                                        )
                                    }

                                    val body = response.body
                                        ?.string()
                                        .orEmpty()

                                    if (body.isBlank()) {
                                        throw IOException(
                                            "Scrape failed: empty response body."
                                        )
                                    }

                                    val noStyles = body.replace(
                                        Regex(
                                            "<style\\b[^<]*(?:(?!</style>)<[^<]*)*</style>",
                                            RegexOption.IGNORE_CASE
                                        ),
                                        " "
                                    )

                                    val noScripts = noStyles.replace(
                                        Regex(
                                            "<script\\b[^<]*(?:(?!</script>)<[^<]*)*</script>",
                                            RegexOption.IGNORE_CASE
                                        ),
                                        " "
                                    )

                                    val cleanText = noScripts
                                        .replace(Regex("<[^>]*>"), " ")
                                        .replace(Regex("\\s+"), " ")
                                        .trim()

                                    val safeText =
                                        cleanText.take(200_000)

                                    val nodeAttestation = JSONObject()
                                        .put("nodeType", "android")
                                        .put(
                                            "appVersion",
                                            currentAttestationAppVersion()
                                        )
                                        .put(
                                            "packageSha256",
                                            packageSha256
                                        )
                                        .put(
                                            "signingCertificateSha256",
                                            signingCertificateSha256
                                        )

                                    aiOutput = JSONObject()
                                        .put("source_url", scrapeUrl)
                                        .put("content", safeText)
                                        .put(
                                            "node_attestation",
                                            nodeAttestation
                                        )
                                        .toString()
                                }
                            } else if (isExactExtractionTask) {
                                aiOutput = runDeterministicExtraction(prompt)
                                    ?: throw IllegalArgumentException(
                                        "Exact extraction plan is not supported."
                                    )
                            } else if (isNeuralTask) {
                                val requestedCapabilities =
                                    routeValues.filter {
                                        it.startsWith(
                                            "Neural-Inference",
                                            ignoreCase = true
                                        )
                                    }

                                check(
                                    requestedCapabilities.all {
                                        it.equals(
                                            "Neural-Inference-3B",
                                            ignoreCase = true
                                        )
                                    }
                                ) {
                                    "Unsupported Android neural capability: " +
                                        requestedCapabilities.joinToString(",")
                                }

                                val readyRuntime =
                                    prepareLevel2RuntimeForTask()

                                val inferenceStartedAt =
                                    SystemClock.elapsedRealtime()

                                logTiming(
                                    "inference_start",
                                    taskId
                                )

                                val neuralResult =
                                    try {
                                        readyRuntime.generate(
                                            prompt
                                        )
                                    } finally {
                                        runCatching {
                                            readyRuntime.close()
                                        }

                                        updateNodeNotification(
                                            currentIdleNotificationText()
                                        )
                                    }

                                val inferenceLatencyMs =
                                    SystemClock.elapsedRealtime() -
                                        inferenceStartedAt

                                logTiming(
                                    "inference_end",
                                    taskId,
                                    inferenceLatencyMs
                                )

                                aiOutput = neuralResult.text

                                Log.i(
                                    "EdgeSwarm",
                                    "Android Level 2 inference complete: " +
                                        "taskId=$taskId, " +
                                        "model=${level2ActiveModelId}, " +
                                        "backend=${level2ActiveBackend}, " +
                                        "latencyMs=$inferenceLatencyMs, " +
                                        "ttftMs=${neuralResult.timeToFirstTokenMs}, " +
                                        "decodeTps=${neuralResult.decodeTokensPerSecond}, " +
                                        "inputTokens=${neuralResult.inputTokens}, " +
                                        "outputTokens=${neuralResult.outputTokens}"
                                )
                            } else {
                                throw IllegalArgumentException(
                                    "Unsupported Android Level 1 task route: " +
                                        "requiredModel=$requiredModel, " +
                                        "requiredCapability=$requiredCapability, " +
                                        "selectedModel=$selectedModel"
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e("EdgeSwarm", "Task execution failed", e)
                            aiOutput = "Execution Failed: ${e.message}"
                            isError = true
                        }

                        val latencyMs = SystemClock.elapsedRealtime() - start
                        logTiming("execution_end", taskId, latencyMs)

                        if (aiOutput.length > 512_000) {
                            aiOutput =
                                aiOutput.take(512_000) +
                                    "...[Truncated]"
                        }

                        val inputTokens = countLevel1InputTokens(prompt)
                        val outputTokens = estimateTokenCountFromText(aiOutput)

                        // ANDROID_RESULT_MODEL_CONTRACT_V1
                        // Report the exact implementation that produced
                        // the result, not only the model advertised by
                        // the most recent heartbeat.
                        val reportedModelIdUsed =
                            when {
                                isNeuralTask ->
                                    level2ActiveModelId
                                        ?.takeIf { it.isNotBlank() }
                                        ?: selectedModel
                                            .takeIf { it.isNotBlank() }

                                isExactExtractionTask ->
                                    "edgeswarm-deterministic-extraction-v1"

                                isComputeTask ->
                                    "edgeswarm-deterministic-compute-v1"

                                isScrapeTask ->
                                    "edgeswarm-deterministic-scraper-v1"

                                else ->
                                    selectedModel
                                        .takeIf { it.isNotBlank() }
                            }

                        val reportedRuntime =
                            if (isNeuralTask) {
                                "litert-lm"
                            } else {
                                "android-kotlin-deterministic-v2"
                            }

                        val reportedRuntimeAcceleration =
                            if (isNeuralTask) {
                                level2ActiveBackend
                                    ?.takeIf { it.isNotBlank() }
                                    ?: "unknown"
                            } else {
                                "cpu"
                            }

                        uploadViaStream(
                            taskId = taskId,
                            workerEmail = userEmail,
                            latency = latencyMs,
                            hwId = hardwareId,
                            aiOutput = aiOutput,
                            inputTokens = inputTokens,
                            outputTokens = outputTokens,
                            finalStatus = if (isError) "error" else "success",
                            modelIdUsed = reportedModelIdUsed,
                            runtime = reportedRuntime,
                            runtimeAcceleration =
                                reportedRuntimeAcceleration
                        )

                        maybeSendNodeHeartbeat(
                            hardwareId,
                            userEmail,
                            emptyList(),
                            force = true
                        )

                        updateNodeNotification(
                            currentIdleNotificationText()
                        )

                    }
                    delay(currentPollIntervalMs())
                }
            } catch (e: CancellationException) {
                Log.d("EdgeSwarm", "Engine loop stopped normally.")
            } catch (e: Exception) {
                Log.e("EdgeSwarm", "Engine Loop Crash: ${e.message}", e)
            } finally {
                releaseExecutionWakeLock()
                stopSelf()
            }
        }
    }


    private fun extractLabeledMatrix(prompt: String, label: String): JSONArray? {
        return try {
            val clean = prompt.replace("compute://", "").trim()
            val labelRegex = Regex("\\b" + Regex.escape(label) + "\\s*=", RegexOption.IGNORE_CASE)
            val match = labelRegex.find(clean) ?: return null

            val start = clean.indexOf("[", match.range.last + 1)
            if (start < 0) return null

            var depth = 0
            var end = -1

            for (idx in start until clean.length) {
                when (clean[idx]) {
                    '[' -> depth += 1
                    ']' -> {
                        depth -= 1
                        if (depth == 0) {
                            end = idx + 1
                            break
                        }
                    }
                }
            }

            if (end <= start) return null

            val raw = clean.substring(start, end)
            val matrix = JSONArray(raw)

            if (matrix.length() == 0) return null

            val width = matrix.getJSONArray(0).length()
            if (width == 0) return null

            for (i in 0 until matrix.length()) {
                val row = matrix.getJSONArray(i)
                if (row.length() != width) return null

                for (j in 0 until row.length()) {
                    row.getDouble(j)
                }
            }

            matrix
        } catch (e: Exception) {
            Log.w("EdgeSwarm", "[COMPUTE] Matrix parse failed for label $label: ${e.message}")
            null
        }
    }

    private fun normalizedNumberForJson(value: Double): Any {
        val roundedInt = round(value)

        return if (abs(value - roundedInt) < 0.000000001) {
            roundedInt.toLong()
        } else {
            round(value * 100000000.0) / 100000000.0
        }
    }

    private fun tryUserMatrixMultiply(prompt: String): String? {
        return try {
            val matrixA = extractLabeledMatrix(prompt, "A") ?: return null
            val matrixB = extractLabeledMatrix(prompt, "B") ?: return null

            val rowsA = matrixA.length()
            val colsA = matrixA.getJSONArray(0).length()
            val rowsB = matrixB.length()
            val colsB = matrixB.getJSONArray(0).length()

            if (colsA != rowsB) {
                return JSONObject()
                    .put("error", "invalid_matrix_dimensions")
                    .put("message", "A columns ($colsA) must equal B rows ($rowsB).")
                    .toString()
            }

            if (rowsA > 100 || colsA > 100 || colsB > 100) {
                return JSONObject()
                    .put("error", "matrix_too_large")
                    .put("message", "User-supplied matrix multiply is capped at 100x100.")
                    .toString()
            }

            val result = JSONArray()

            for (i in 0 until rowsA) {
                val resultRow = JSONArray()

                for (j in 0 until colsB) {
                    var total = 0.0

                    for (k in 0 until colsA) {
                        total += matrixA.getJSONArray(i).getDouble(k) * matrixB.getJSONArray(k).getDouble(j)
                    }

                    resultRow.put(normalizedNumberForJson(total))
                }

                result.put(resultRow)
            }

            JSONObject()
                .put("response", result)
                .toString()
        } catch (e: Exception) {
            Log.w("EdgeSwarm", "[COMPUTE] User matrix multiply failed: ${e.message}")
            null
        }
    }


    private fun runDeterministicCompute(prompt: String, checkpointIndicesJson: JSONArray? = null): String {
        val userMatrixOutput = tryUserMatrixMultiply(prompt)
        if (userMatrixOutput != null) {
            Log.d("EdgeSwarm", "[COMPUTE] Completed user-supplied matrix multiply.")
            return userMatrixOutput
        }

        val sizeRegex = Regex("(\\d+)x\\1")
        val explicitSizeRegex = Regex("size\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)

        val match = sizeRegex.find(prompt)
        val explicitSizeMatch = explicitSizeRegex.find(prompt)

        var size = explicitSizeMatch?.groupValues?.get(1)?.toIntOrNull()
            ?: match?.groupValues?.get(1)?.toIntOrNull()
            ?: 10

        if (size > 100) {
            Log.w("EdgeSwarm", "Requested size ${size}x${size} exceeds Android Level 1 limit. Using 100x100.")
            size = 100
        }

        if (size < 1) {
            size = 1
        }

        // Keep the same deterministic seed logic so Android matches the backend verifier.
        val seedText = size.toString()
        val seedInt = seedText.sumOf { it.code }

        val matrixA = FloatArray(size * size)
        val matrixB = FloatArray(size * size)
        val result = FloatArray(size * size)

        for (i in 0 until size * size) {
            matrixA[i] = (((i + seedInt) % 1000).toFloat() / 1000.0f)
            matrixB[i] = (((i + seedInt + 999) % 1000).toFloat() / 1000.0f)
        }

        for (row in 0 until size) {
            for (col in 0 until size) {
                var sum = 0.0f

                for (k in 0 until size) {
                    sum += matrixA[row * size + k] * matrixB[k * size + col]
                }

                result[row * size + col] = sum
            }
        }

        val fullBuffer = ByteBuffer.allocate(result.size * 4)
        fullBuffer.order(ByteOrder.LITTLE_ENDIAN)

        for (value in result) {
            fullBuffer.putFloat(value)
        }

        val fullBytes = fullBuffer.array()
        val resultHashBytes = java.security.MessageDigest.getInstance("SHA-256").digest(fullBytes)
        val resultHash = Numeric.toHexStringNoPrefix(resultHashBytes)

        val outputLength = minOf(1000, result.size)
        val sampleBuffer = ByteBuffer.allocate(outputLength * 4)
        sampleBuffer.order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until outputLength) {
            sampleBuffer.putFloat(result[i])
        }

        val sampleBase64 = Base64.encodeToString(sampleBuffer.array(), Base64.NO_WRAP)

        val requestedCheckpointIndices = mutableListOf<Int>()

        if (checkpointIndicesJson != null) {
            for (i in 0 until checkpointIndicesJson.length()) {
                val index = checkpointIndicesJson.optInt(i, -1)
                if (index >= 0 && index < result.size) {
                    requestedCheckpointIndices.add(index)
                }
            }
        }

        val fallbackCheckpointIndices = listOf(
            0,
            result.size / 3,
            (result.size * 2) / 3,
            result.size - 1
        ).filter { it >= 0 && it < result.size }

        val checkpointIndices = if (requestedCheckpointIndices.isNotEmpty()) {
            requestedCheckpointIndices.distinct()
        } else {
            fallbackCheckpointIndices.distinct()
        }

        val checkpointValues = JSONObject()
        for (index in checkpointIndices) {
            checkpointValues.put(index.toString(), result[index].toDouble())
        }

        Log.d("EdgeSwarm", "Returning ${checkpointIndices.size} compute checkpoints: $checkpointIndices")

        return JSONObject().apply {
            put("type", "matrix_multiply")
            put("size", size)
            put("algorithmVersion", "1.0")
            put("resultHash", resultHash)
            put("sampleBase64", sampleBase64)
            put("checkpointValues", checkpointValues)
        }.toString()
    }

    private fun extractFirstHttpUrl(value: String): String? {
        val normalized = value.replace("\\/", "/")
        val match = Regex(
            """https://[A-Za-z0-9._~:/?#\[\]@!$&'()*+,;=%-]+""",
            RegexOption.IGNORE_CASE
        ).find(normalized) ?: return null

        return cleanExactValue(match.value)
    }

    private fun cleanExactValue(value: String): String {
        return value
            .trim()
            .trim(' ', '\t', '\r', '\n', '"', '\'', '`')
            .trimEnd('.', ',', ';', ')', ']', '}')
            .trim()
    }

    private fun chooseExactMatch(
        values: List<String>,
        selectionRule: String
    ): String? {
        val cleaned = values
            .map(::cleanExactValue)
            .filter { it.isNotBlank() }

        if (cleaned.isEmpty()) return null

        return if (selectionRule.contains("last", ignoreCase = true)) {
            cleaned.last()
        } else {
            cleaned.first()
        }
    }

    private fun extractLabeledToken(
        sources: List<String>,
        labels: List<String>,
        selectionRule: String
    ): String? {
        if (labels.isEmpty()) return null

        val labelPattern = labels.joinToString("|") {
            Regex.escape(it)
        }

        // ANDROID_OVERLAPPING_LABEL_EXTRACTION_V1
        // Look-ahead allows overlapping labels. For example:
        // "CODE: CODE: VALUE" produces both "CODE:" and "VALUE".
        val regex = Regex(
            """(?i)(?=\b($labelPattern)\b\s*(?:is|:|=|-)\s*""" +
                """([A-Za-z0-9][A-Za-z0-9._:/@%+\-]*))"""
        )

        val matches = sources.flatMap { source ->
            regex.findAll(source)
                .map { it.groupValues[2] }
                .toList()
        }

        val normalizedLabels = labels
            .map { it.trim().lowercase() }
            .toSet()

        // A repeated label is an indirection, not the final answer.
        // Discard "CODE:" when another CODE label follows it.
        val resolvedMatches = matches.filterNot { candidate ->
            cleanExactValue(candidate)
                .trimEnd(':', '=', '-')
                .trim()
                .lowercase() in normalizedLabels
        }

        return chooseExactMatch(
            if (resolvedMatches.isNotEmpty()) {
                resolvedMatches
            } else {
                matches
            },
            selectionRule
        )
    }

    private fun parseExactExtractionPlan(
        cleanPrompt: String
    ): JSONObject? {
        if (!cleanPrompt.contains(
                "EXACT_EXTRACTION_PLAN_V1:",
                ignoreCase = true
            )
        ) {
            return null
        }

        val jsonText = cleanPrompt
            .substringAfter("EXACT_EXTRACTION_PLAN_V1:")
            .trim()

        return runCatching { JSONObject(jsonText) }.getOrNull()
    }

    private fun runDeterministicExtraction(
        prompt: String
    ): String? {
        val cleanPrompt = prompt
            .removePrefix("prompt://")
            .trim()

        val plan = parseExactExtractionPlan(cleanPrompt)

        val fieldType = plan
            ?.optString("fieldType", "")
            ?.trim()
            ?.lowercase()
            .orEmpty()

        val planSelectionRule = plan
            ?.optString("selectionRule", "")
            ?.trim()
            ?.lowercase()
            .orEmpty()

        // ANDROID_UNWRAPPED_EXTRACTION_SELECTION_V1
        // The backend may deliver the original natural-language prompt
        // instead of the full EXACT_EXTRACTION_PLAN_V1 envelope.
        val naturalInstructionForSelection = plan
            ?.optString("originalPrompt", "")
            ?.trim()
            .orEmpty()
            .ifBlank { cleanPrompt }

        val selectionRule = when {
            planSelectionRule.isNotBlank() -> planSelectionRule

            Regex("""(?i)\b(?:last|final)\b""")
                .containsMatchIn(
                    naturalInstructionForSelection
                ) -> "last"

            else -> "first"
        }

        val anchorPhrase = plan
            ?.optString("anchorPhrase", "")
            ?.trim()
            .orEmpty()

        val planText = plan
            ?.optString("text", "")
            ?.trim()
            .orEmpty()

        val originalPrompt = plan
            ?.optString("originalPrompt", "")
            ?.trim()
            .orEmpty()

        val fallbackText = Regex(
            """(?is)\btext\s*:\s*(.*)$"""
        ).find(cleanPrompt)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()

        val targetText = when {
            planText.isNotBlank() -> planText
            fallbackText.isNotBlank() -> fallbackText
            originalPrompt.isNotBlank() -> originalPrompt
            else -> cleanPrompt
        }

        val sources = listOf(
            targetText,
            originalPrompt
        ).filter { it.isNotBlank() }

        val intentText = listOf(
            fieldType,
            anchorPhrase,
            originalPrompt,
            targetText
        ).joinToString(" ").lowercase()

        // Text/code plans are common for smoke tests and customer-defined labels.
        if (
            intentText.contains("code") ||
            targetText.trimStart().startsWith(
                "CODE",
                ignoreCase = true
            )
        ) {
            extractLabeledToken(
                sources,
                listOf("code"),
                selectionRule
            )?.let { return it }
        }

        if (
            fieldType == "email" ||
            intentText.contains("email")
        ) {
            val regex = Regex(
                """[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}"""
            )

            val values = sources.flatMap { source ->
                regex.findAll(source).map { it.value }.toList()
            }

            chooseExactMatch(values, selectionRule)
                ?.let { return it }
        }

        if (
            fieldType == "url" ||
            intentText.contains("url") ||
            intentText.contains("website") ||
            intentText.contains("link")
        ) {
            sources.asSequence()
                .mapNotNull(::extractFirstHttpUrl)
                .firstOrNull()
                ?.let { return it }
        }

        if (
            fieldType == "phone" ||
            intentText.contains("phone")
        ) {
            val regex = Regex(
                """\+?\d[\d\s().\-]{7,}\d"""
            )

            val values = sources.flatMap { source ->
                regex.findAll(source).map { it.value }.toList()
            }

            chooseExactMatch(values, selectionRule)
                ?.let { return it }
        }

        if (
            fieldType == "wallet" ||
            intentText.contains("wallet address") ||
            intentText.contains("ethereum address")
        ) {
            val regex = Regex("""0x[a-fA-F0-9]{40}""")

            val values = sources.flatMap { source ->
                regex.findAll(source).map { it.value }.toList()
            }

            chooseExactMatch(values, selectionRule)
                ?.let { return it }
        }

        if (
            fieldType == "percentage" ||
            intentText.contains("percentage") ||
            intentText.contains("percent")
        ) {
            val regex = Regex(
                """\b\d+(?:\.\d+)?\s*%"""
            )

            val values = sources.flatMap { source ->
                regex.findAll(source).map { it.value }.toList()
            }

            chooseExactMatch(values, selectionRule)
                ?.let { return it.replace(" ", "") }
        }

        if (
            fieldType == "date" ||
            intentText.contains("date")
        ) {
            val datePatterns = listOf(
                Regex("""\b\d{4}-\d{2}-\d{2}\b"""),
                Regex("""\b\d{1,2}/\d{1,2}/\d{2,4}\b"""),
                Regex("""\b\d{1,2}\.\d{1,2}\.\d{2,4}\b""")
            )

            val values = sources.flatMap { source ->
                datePatterns.flatMap { regex ->
                    regex.findAll(source).map { it.value }.toList()
                }
            }

            chooseExactMatch(values, selectionRule)
                ?.let { return it }
        }

        if (
            fieldType == "version" ||
            intentText.contains("version")
        ) {
            extractLabeledToken(
                sources,
                listOf("version", "app version"),
                selectionRule
            )?.let { return it }

            val regex = Regex(
                """\bv?\d+(?:\.\d+){1,4}(?:[-+][A-Za-z0-9.-]+)?\b""",
                RegexOption.IGNORE_CASE
            )

            val values = sources.flatMap { source ->
                regex.findAll(source).map { it.value }.toList()
            }

            chooseExactMatch(values, selectionRule)
                ?.let { return it }
        }

        val identifierLabels = buildList {
            if (intentText.contains("invoice")) {
                add("invoice")
                add("invoice number")
                add("invoice id")
            }
            if (intentText.contains("order")) {
                add("order")
                add("order number")
                add("order id")
            }
            if (intentText.contains("tracking")) {
                add("tracking")
                add("tracking number")
                add("tracking id")
            }
        }

        extractLabeledToken(
            sources,
            identifierLabels,
            selectionRule
        )?.let { return it }

        if (
            fieldType == "amount" ||
            intentText.contains("amount") ||
            intentText.contains("price") ||
            intentText.contains("cost") ||
            intentText.contains("usd") ||
            intentText.contains("dollar")
        ) {
            val patterns = listOf(
                Regex("""\$\s*([0-9]+(?:\.[0-9]+)?)"""),
                Regex(
                    """(?i)\b(?:usd|amount|price|cost)\s*(?:is|:|=)?\s*""" +
                        """([0-9]+(?:\.[0-9]+)?)"""
                ),
                Regex(
                    """(?i)\b([0-9]+(?:\.[0-9]+)?)\s+dollars?\b"""
                )
            )

            val values = sources.flatMap { source ->
                patterns.flatMap { regex ->
                    regex.findAll(source).mapNotNull { match ->
                        match.groupValues.getOrNull(1)
                            ?.takeIf { it.isNotBlank() }
                    }.toList()
                }
            }

            chooseExactMatch(values, selectionRule)
                ?.let { return it }
        }

        if (
            fieldType == "number" ||
            intentText.contains("number")
        ) {
            val regex = Regex("""\b\d+(?:\.\d+)?\b""")

            val values = sources.flatMap { source ->
                regex.findAll(source).map { it.value }.toList()
            }

            chooseExactMatch(values, selectionRule)
                ?.let { return it }
        }

        if (
            fieldType == "ticker" ||
            intentText.contains("ticker") ||
            intentText.contains("stock symbol")
        ) {
            extractLabeledToken(
                sources,
                listOf(
                    "ticker",
                    "ticker symbol",
                    "stock symbol",
                    "trading symbol"
                ),
                selectionRule
            )?.uppercase()?.let { return it }

            val regex = Regex("""\b[A-Z]{1,6}\b""")
            val blacklist = setOf(
                "CODE",
                "RETURN",
                "ONLY",
                "TEXT",
                "FINAL",
                "VALUE"
            )

            val values = sources.flatMap { source ->
                regex.findAll(source)
                    .map { it.value }
                    .filterNot { it in blacklist }
                    .toList()
            }

            chooseExactMatch(values, selectionRule)
                ?.let { return it }
        }

        if (
            fieldType == "country" ||
            intentText.contains("country")
        ) {
            val labeled = Regex(
                """(?i)\bcountry\b\s*(?:is|:|=|-)\s*""" +
                    """([A-Za-z][A-Za-z .'-]{1,60})"""
            )

            val values = sources.flatMap { source ->
                labeled.findAll(source)
                    .map { it.groupValues[1] }
                    .toList()
            }

            chooseExactMatch(values, selectionRule)
                ?.let { return it }
        }

        if (
            fieldType == "company_name" ||
            fieldType == "company" ||
            intentText.contains("company name")
        ) {
            val labeled = Regex(
                """(?i)\bcompany(?:\s+name)?\b\s*(?:is|:|=|-)\s*""" +
                    """([A-Za-z0-9][A-Za-z0-9 &.,'\-]{1,100})"""
            )

            val values = sources.flatMap { source ->
                labeled.findAll(source)
                    .map { it.groupValues[1] }
                    .toList()
            }

            chooseExactMatch(values, selectionRule)
                ?.let { return it }
        }

        // Generic plain-text plan fallback:
        // 1. Honor a real anchor inside the plan text.
        // 2. Otherwise return the value after a single leading label.
        // 3. Finally return a single non-empty plan text value.
        if (anchorPhrase.isNotBlank()) {
            val anchorIndex = targetText.indexOf(
                anchorPhrase,
                ignoreCase = true
            )

            if (anchorIndex >= 0) {
                val afterAnchor = targetText
                    .substring(anchorIndex + anchorPhrase.length)
                    .trimStart(' ', '\t', ':', '=', '-')

                cleanExactValue(afterAnchor)
                    .takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
        }

        val leadingLabel = Regex(
            """(?s)^\s*[A-Za-z][A-Za-z0-9 _-]{0,40}\s*[:=]\s*(.+?)\s*$"""
        ).matchEntire(targetText)

        leadingLabel
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::cleanExactValue)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        if (
            plan != null &&
            targetText.isNotBlank() &&
            !targetText.contains('\n')
        ) {
            return cleanExactValue(targetText)
                .takeIf { it.isNotBlank() }
        }

        return null
    }


    private fun getNodeCredentials(email: String): Credentials {
        val privateKeyHex = WalletVault.loadPrivateKey(this, email)
            ?: throw IllegalStateException(
                "Node wallet is not provisioned. Open the app and complete authenticated wallet sync."
            )

        return Credentials.create(privateKeyHex)
    }

    private fun buildPlayIntegrityNonce(challengeId: String, nonce: String): String {
        val raw = "$challengeId:$nonce"
        return Base64.encodeToString(
            raw.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun requestPlayIntegrityTokenSafely(playIntegrityNonce: String): String? {
        return try {
            val integrityManager = IntegrityManagerFactory.create(applicationContext)

            val builder = IntegrityTokenRequest.builder()
                .setNonce(playIntegrityNonce)
                .setCloudProjectNumber(BuildConfig.PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER)

            val response = Tasks.await(
                integrityManager.requestIntegrityToken(builder.build()),
                15,
                TimeUnit.SECONDS
            )

            val token = response.token()

            if (token.isNullOrBlank()) {
                Log.w("EdgeSwarm", "Play Integrity returned empty token.")
                null
            } else {
                Log.d("EdgeSwarm", "Play Integrity token acquired.")
                token
            }
        } catch (e: Exception) {
            Log.w("EdgeSwarm", "Play Integrity token request failed: ${e.message}")
            null
        }
    }


    private fun signMessageSecurely(message: String, workerEmail: String): String {
        return try {
            val nodeCredentials = getNodeCredentials(workerEmail)
            val messageBytes = message.toByteArray(Charsets.UTF_8)

            val signatureData = Sign.signPrefixedMessage(messageBytes, nodeCredentials.ecKeyPair)

            val sigBytes = ByteArray(65)
            System.arraycopy(signatureData.r, 0, sigBytes, 0, 32)
            System.arraycopy(signatureData.s, 0, sigBytes, 32, 32)

            var vVal = signatureData.v[0].toInt()
            if (vVal < 27) vVal += 27
            sigBytes[64] = vVal.toByte()

            "0x" + Numeric.toHexStringNoPrefix(sigBytes)
        } catch (e: Exception) {
            Log.e("EdgeSwarm", "Node attestation signing failed: ${e.message}")
            ""
        }
    }

    private fun performNodeAttestationPhase1(
        hardwareId: String,
        providerEmail: String,
        force: Boolean = false
    ) {
        try {
            val now = System.currentTimeMillis()

            if (!force && now - lastAttestationAttemptMs < attestationIntervalMs) {
                return
            }

            lastAttestationAttemptMs = now

            val walletAddress = nodeWalletAddress ?: getNodeCredentials(providerEmail).address
            nodeWalletAddress = walletAddress

            val challengeJson = JSONObject()
                .put("providerEmail", providerEmail)
                .put("hardwareId", hardwareId)
                .put("walletAddress", walletAddress)
                .put("nodeType", "android")
                .put("appVersion", currentAttestationAppVersion())
                .put("packageName", packageName)
                .put("packageSha256", packageSha256)
                .put("signingCertificateSha256", signingCertificateSha256)

            val challengeBody = challengeJson
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val challengeRequest = authenticatedRequestBuilder(attestationChallengeEndpoint())
                .post(challengeBody)
                .build()

            httpClient.newCall(challengeRequest).execute().use { challengeResponse ->
                val challengeText = challengeResponse.body?.string().orEmpty()

                if (!challengeResponse.isSuccessful) {
                    handleTerminalControlPlaneFailure(challengeResponse.code, "Attestation challenge")
                    Log.w("EdgeSwarm", "Attestation challenge failed: ${challengeResponse.code} $challengeText")
                    return
                }

                val challenge = JSONObject(challengeText)
                val challengeId = challenge.getString("challengeId")
                val nonce = challenge.getString("nonce")
                val message = challenge.getString("message")

                val playIntegrityNonce = buildPlayIntegrityNonce(challengeId, nonce)
                val playIntegrityToken = requestPlayIntegrityTokenSafely(playIntegrityNonce)

                val signature = signMessageSecurely(message, providerEmail)

                if (signature.isBlank() || signature == "0x0") {
                    Log.w("EdgeSwarm", "Attestation signature was empty.")
                    return
                }

                val verifyJson = JSONObject()
                    .put("challengeId", challengeId)
                    .put("providerEmail", providerEmail)
                    .put("hardwareId", hardwareId)
                    .put("walletAddress", walletAddress)
                    .put("nodeType", "android")
                    .put("appVersion", currentAttestationAppVersion())
                    .put("packageName", packageName)
                    .put("packageSha256", packageSha256)
                    .put("signingCertificateSha256", signingCertificateSha256)
                    .put("signature", signature)
                    .put("playIntegrityNonce", playIntegrityNonce)
                    .put("playIntegrityToken", playIntegrityToken ?: JSONObject.NULL)

                val verifyBody = verifyJson
                    .toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())

                val verifyRequest = authenticatedRequestBuilder(attestationVerifyEndpoint())
                    .post(verifyBody)
                    .build()

                httpClient.newCall(verifyRequest).execute().use { verifyResponse ->
                    val verifyText = verifyResponse.body?.string().orEmpty()

                    if (verifyResponse.isSuccessful) {
                        val verifyJson = runCatching {
                            JSONObject(verifyText)
                        }.getOrNull()

                        Log.d(
                            "EdgeSwarm",
                            "Node attestation verified -> " +
                                "trust=${verifyJson?.optString("trustStatus", "unknown")} " +
                                "status=${verifyJson?.optString("attestationStatus", "unknown")} " +
                                "paid=${verifyJson?.optBoolean("canReceivePaidJobs", false)}"
                        )
                    } else {
                        handleTerminalControlPlaneFailure(verifyResponse.code, "Attestation verification")
                        Log.w("EdgeSwarm", "Node attestation verify failed: ${verifyResponse.code} $verifyText")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("EdgeSwarm", "Node attestation phase 1 failed: ${e.message}")
        }
    }


    private fun signPayloadSecurely(taskId: Int, properFileHash: String, hwId: String, workerEmail: String): String {
        return try {
            val nodeCredentials = getNodeCredentials(workerEmail)

            val expectedMessage = "Task:$taskId|Score:100|Hash:$properFileHash|HW:$hwId"
            val messageBytes = expectedMessage.toByteArray(Charsets.UTF_8)

            val signatureData = Sign.signPrefixedMessage(messageBytes, nodeCredentials.ecKeyPair)

            val sigBytes = ByteArray(65)
            System.arraycopy(signatureData.r, 0, sigBytes, 0, 32)
            System.arraycopy(signatureData.s, 0, sigBytes, 32, 32)

            var vVal = signatureData.v[0].toInt()
            if (vVal < 27) vVal += 27
            sigBytes[64] = vVal.toByte()

            val finalSig = "0x" + Numeric.toHexStringNoPrefix(sigBytes)
            logTiming("signing_complete", taskId)
            finalSig
        } catch (e: Exception) {
            Log.e("EdgeSwarm", "Signature Math Failed: ${e.message}")
            "0x0"
        }
    }

    private fun estimateTokenCountFromText(text: String?): Int {
        val value = text ?: ""
        if (value.isBlank()) return 0
        return maxOf(1, (value.length + 3) / 4)
    }

    private fun countLevel1InputTokens(prompt: String): Int {
        val cleanPrompt = prompt
            .removePrefix("prompt://")
            .removePrefix("compute://")
            .trim()

        val customerPrompt = parseExactExtractionPlan(cleanPrompt)
            ?.optString("originalPrompt", "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: cleanPrompt

        return estimateTokenCountFromText(customerPrompt)
    }

    private fun uploadViaStream(
        taskId: Int,
        workerEmail: String,
        latency: Long,
        hwId: String,
        aiOutput: String,
        inputTokens: Int,
        outputTokens: Int,
        finalStatus: String,
        modelIdUsed: String?,
        runtime: String,
        runtimeAcceleration: String
    ): Boolean {
        Log.d("EdgeSwarm", "Uploading payload to: $gcpUploadUrl")

        return try {
            val nodeCredentials = getNodeCredentials(workerEmail)

            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(
                aiOutput.toByteArray(Charsets.UTF_8)
            )
            val properFileHash =
                Numeric.toHexStringNoPrefix(hashBytes)

            val realProductionSignature = signPayloadSecurely(
                taskId,
                properFileHash,
                hwId,
                workerEmail
            )

            if (
                realProductionSignature.isBlank() ||
                realProductionSignature == "0x0"
            ) {
                throw IllegalStateException(
                    "Result signature could not be produced."
                )
            }

            val payloadJson = JSONObject()
                .put("taskId", taskId)
                .put("worker", nodeCredentials.address)
                .put("providerEmail", workerEmail)
                .put("score", 100)
                .put("signature", realProductionSignature)
                .put("hardwareId", hwId)
                .put("nodeType", "android")
                .put(
                    "appVersion",
                    currentAttestationAppVersion()
                )
                .put(
                    "releaseChannel",
                    EdgeSwarmConfig.releaseChannel
                )
                .put("packageSha256", packageSha256)
                .put(
                    "signingCertificateSha256",
                    signingCertificateSha256
                )
                .put("aiOutput", aiOutput)
                .put("aiTranslation", JSONObject.NULL)
                .put("status", finalStatus)
                .put("latency_ms", latency)
                .put("inputTokens", inputTokens)
                .put("outputTokens", outputTokens)
                .put("tokenCountMethod", "char_estimate_v1")
                .put(
                    "model_id_used",
                    modelIdUsed ?: JSONObject.NULL
                )
                .put(
                    "modelIdUsed",
                    modelIdUsed ?: JSONObject.NULL
                )
                .put("runtime", runtime)
                .put(
                    "runtime_acceleration",
                    runtimeAcceleration
                )
                .put(
                    "runtimeAcceleration",
                    runtimeAcceleration
                )

            val rootJson = JSONObject()
                .put("fileHash", properFileHash)
                .put("payload", payloadJson)

            val requestBody = rootJson
                .toString()
                .toRequestBody(
                    "application/json; charset=utf-8".toMediaType()
                )

            Log.d(
                "EdgeSwarm",
                "Task output prepared -> taskId=$taskId " +
                    "status=$finalStatus bytes=${aiOutput.toByteArray().size} " +
                    "sha256=$properFileHash " +
                    "modelIdUsed=${modelIdUsed ?: "none"} " +
                    "runtime=$runtime " +
                    "acceleration=$runtimeAcceleration"
            )

            if (BuildConfig.DEBUG) {
                Log.d(
                    "EdgeSwarm",
                    "Task output preview -> taskId=$taskId " +
                        aiOutput.take(240)
                )
            }

            val maxAttempts = 3
            var authRefreshUsed = false

            for (attempt in 1..maxAttempts) {
                val request = authenticatedRequestBuilder(
                    gcpUploadUrl
                )
                    .post(requestBody)
                    .build()

                val uploadStartedAt =
                    SystemClock.elapsedRealtime()

                logTiming("upload_request_start", taskId)

                try {
                    httpClient.newCall(request).execute().use {
                        response ->
                        val elapsed =
                            SystemClock.elapsedRealtime() -
                                uploadStartedAt

                        var responseCode = response.code
                        var responseText =
                            response.body?.string().orEmpty()

                        logTiming(
                            "upload_response_received",
                            taskId,
                            elapsed
                        )

                        Log.d(
                            "EdgeSwarm",
                            "Upload Result: ${response.code} - " +
                                response.message +
                                " attempt=$attempt"
                        )

                        if (response.isSuccessful) {
                            return true
                        }

                        if (response.code == 401 && !authRefreshUsed) {
                            authRefreshUsed = true
                            if (refreshAccessTokenAfterUnauthorized() != null) {
                                val authRetryRequest =
                                    authenticatedRequestBuilder(
                                        gcpUploadUrl
                                    )
                                        .post(requestBody)
                                        .build()

                                Log.w(
                                    "EdgeSwarm",
                                    "Result upload bearer refreshed; retrying once."
                                )

                                httpClient.newCall(authRetryRequest)
                                    .execute()
                                    .use { authRetryResponse ->
                                        responseCode =
                                            authRetryResponse.code
                                        responseText =
                                            authRetryResponse.body
                                                ?.string()
                                                .orEmpty()

                                        Log.d(
                                            "EdgeSwarm",
                                            "Upload auth retry: " +
                                                authRetryResponse.code +
                                                " - " +
                                                authRetryResponse.message
                                        )

                                        if (authRetryResponse.isSuccessful) {
                                            return true
                                        }
                                    }
                            }
                        }

                        handleTerminalControlPlaneFailure(
                            responseCode,
                            "Result upload"
                        )

                        Log.e(
                            "EdgeSwarm",
                            "Server rejected payload: $responseText"
                        )

                        val retryable =
                            responseCode == 408 ||
                            responseCode == 425 ||
                            responseCode == 429 ||
                            responseCode in 500..599

                        if (!retryable || attempt == maxAttempts) {
                            return false
                        }
                    }
                } catch (e: IOException) {
                    Log.w(
                        "EdgeSwarm",
                        "Result upload network failure " +
                            "attempt=$attempt: ${e.message}"
                    )

                    if (attempt == maxAttempts) {
                        return false
                    }
                }

                SystemClock.sleep(750L * attempt)
            }

            false
        } catch (e: Exception) {
            Log.e(
                "EdgeSwarm",
                "Upload pipeline failed: ${e.message}",
                e
            )
            false
        }
    }

    private fun startLevel2SelfTestIfEnabled() {
        if (!allowNeuralTasks) {
            updateNodeNotification(
                currentIdleNotificationText()
            )
            return
        }

        val runtime = level2Runtime ?: return

        level2SelfTestPassed = false
        level2ActiveModelId = null
        level2ActiveCapability = null
        level2ActiveBackend = null
        level2ActiveModelPath = null
        level2ActiveBackendType = null
        level2LastError = null

        publishLevel2Status(
            "Running Gemma 4 Tensor G5 NPU self-test..."
        )

        updateNodeNotification(
            "Preparing Level 2 - Gemma 4 E2B"
        )

        serviceScope.launch(Dispatchers.IO) {
            try {
                Log.i(
                    "EdgeSwarm",
                    "Starting Android Level 2 runtime self-test."
                )

                val result =
                    AndroidLevel2SelfTestCoordinator(
                        this@SentinelService
                    ).initializeAndRun(runtime)

                if (!isServiceRunning) {
                    runCatching { runtime.close() }
                    return@launch
                }

                level2SelfTestPassed = true
                level2ActiveModelId =
                    result.modelId
                level2ActiveCapability =
                    result.capability
                level2ActiveBackend =
                    result.backend.telemetryName
                level2ActiveModelPath =
                    result.modelFilePath
                level2ActiveBackendType =
                    result.backend
                level2LastError = null

                // MOBILE_COLD_READY_LEVEL2_V1
                // Preserve verified eligibility while releasing
                // the large LiteRT-LM engine during idle time.
                runCatching {
                    runtime.close()
                }

                publishLevel2Status(
                    "Level 2 ready - Gemma 4 E2B - " +
                        "${result.backend.telemetryName.uppercase()}"
                )

                updateNodeNotification(
                    currentIdleNotificationText()
                )

                Log.i(
                    "EdgeSwarm",
                    "Android Level 2 self-test passed: " +
                        "model=${result.modelId}, " +
                        "capability=${result.capability}, " +
                        "backend=${result.backend.telemetryName}, " +
                        "ttftMs=${result.timeToFirstTokenMs}, " +
                        "decodeTps=${result.decodeTokensPerSecond}, " +
                        "inputTokens=${result.inputTokens}, " +
                        "outputTokens=${result.outputTokens}"
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                level2SelfTestPassed = false
                level2ActiveModelId = null
                level2ActiveCapability = null
                level2ActiveBackend = null
                level2ActiveModelPath = null
                level2ActiveBackendType = null
                level2LastError =
                    error.message ?: error.javaClass.simpleName

                publishLevel2Status(
                    "Level 2 self-test failed: " +
                        (
                            error.message
                                ?: error.javaClass.simpleName
                        )
                )

                updateNodeNotification(
                    currentIdleNotificationText()
                )

                runCatching {
                    runtime.close()
                }

                Log.e(
                    "EdgeSwarm",
                    "Android Level 2 self-test failed. " +
                        "Node remains Level 1.",
                    error
                )
            }
        }
    }


    private fun fetchTaskFromMempool(hwId: String, providerEmail: String): JSONObject? {
        val pollStartedAt = SystemClock.elapsedRealtime()
        logTiming("poll_request_start")

        return try {
            val capabilities = getAndroidCapabilities().joinToString(",")
            fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

            val requestUrl =
                "$gcpJobsUrl?hardwareId=${enc(hwId)}" +
                    "&providerEmail=${enc(providerEmail)}" +
                    "&capabilities=${enc(capabilities)}" +
                    "&limit=$androidConcurrencyLimit" +
                    "&version=$appVersion" +
                    "&appType=$appType"

            var request = authenticatedRequestBuilder(requestUrl).build()
            var authRefreshUsed = false

            for (authAttempt in 0..1) {
                var retryAfterRefresh = false

                httpClient.newCall(request).execute().use { response ->
                    val elapsed = SystemClock.elapsedRealtime() - pollStartedAt
                    val responseText = response.body?.string().orEmpty()
                    logTiming("poll_response_received", elapsedMs = elapsed)

                    if (!response.isSuccessful) {
                        if (response.code == 401 && !authRefreshUsed) {
                            authRefreshUsed = true
                            if (refreshAccessTokenAfterUnauthorized() != null) {
                                request = authenticatedRequestBuilder(requestUrl).build()
                                retryAfterRefresh = true
                                Log.w("EdgeSwarm", "Task polling bearer refreshed; retrying once.")
                            }
                        }

                        if (!retryAfterRefresh) {
                            handleTerminalControlPlaneFailure(response.code, "Task polling")
                            Log.w("EdgeSwarm", "Fetch task failed: HTTP " + response.code + " - " + responseText)
                            return null
                        }
                    } else {
                        val json = JSONObject(responseText.ifBlank { "{}" })

                        if (json.has("task") && !json.isNull("task")) {
                            return json.getJSONObject("task")
                        }

                        if (json.has("tasks") && !json.isNull("tasks")) {
                            val tasks = json.getJSONArray("tasks")
                            if (tasks.length() > 0) {
                                return tasks.getJSONObject(0)
                            }
                        }

                        return null
                    }
                }

                if (!retryAfterRefresh) return null
            }

            null
        } catch (e: Exception) {
            val elapsed = SystemClock.elapsedRealtime() - pollStartedAt
            logTiming("poll_request_failed", elapsedMs = elapsed)
            Log.w("EdgeSwarm", "Fetch task failed: ${e.message}")
            null
        }
    }

    private fun prepareLevel2RuntimeForTask():
        AndroidLevel2Runtime {
        check(isLevel2Ready()) {
            "Android Level 2 is not currently eligible."
        }

        val modelPath =
            level2ActiveModelPath
                ?: error(
                    "Verified Android Level 2 model path is unavailable."
                )

        val backend =
            level2ActiveBackendType
                ?: error(
                    "Verified Android Level 2 backend is unavailable."
                )

        val runtime =
            level2Runtime
                ?: AndroidLevel2Runtime(
                    cacheDir =
                        filesDir.resolve(
                            "level2_litert_lm_cache"
                        )
                ).also {
                    level2Runtime = it
                }

        if (!runtime.isReady) {
            runtime.initialize(
                modelFile = File(modelPath),
                backend = backend,
                maxNumTokens =
                    if (
                        backend ==
                            AndroidLevel2Backend.NPU
                    ) {
                        1024
                    } else {
                        2048
                    },
                nativeLibraryDir =
                    if (
                        backend ==
                            AndroidLevel2Backend.NPU
                    ) {
                        applicationInfo.nativeLibraryDir
                    } else {
                        null
                    }
            )
        }

        return runtime
    }

    private fun isLevel2Ready(): Boolean {
        return allowNeuralTasks &&
            level2SelfTestPassed &&
            !isThermallyConstrained() &&
            !level2ActiveModelId.isNullOrBlank() &&
            !level2ActiveModelPath.isNullOrBlank() &&
            level2ActiveBackendType != null &&
            level2ActiveCapability.equals(
                "Neural-Inference-3B",
                ignoreCase = true
            )
    }

    private fun getAndroidCapabilities(): List<String> {
        return buildList {
            add("Exact-Extraction")

            if (allowScrapingTasks) {
                add("Data-Scraper")
            }

            if (allowComputeTasks) {
                add("Distributed-Compute")
            }

            if (isLevel2Ready()) {
                add("Neural-Inference-3B")
            }
        }
    }

}
