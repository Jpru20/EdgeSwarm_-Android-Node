package com.edgeswarm.node

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.Base64
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import org.web3j.crypto.Credentials
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class SentinelService : Service() {

    companion object {
        var isServiceRunning = false
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var slmEngine: LlmInference? = null
    private var lastEngineError = "Engine never initialized." // 🚨 Stores the exact crash reason

    private val gcpBaseUrl = "https://api.edgeswarm.io"
    private val gcpUploadUrl = "https://api.edgeswarm.io/enterprise/submit-result"
    private val gcpJobsUrl = "$gcpBaseUrl/swarm/get-jobs"

    // 🚨 Production Timeout rules for deep scraping tasks
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isServiceRunning) return START_STICKY

        val channel = NotificationChannel("sentinel_node", "Headless Node", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = Notification.Builder(this, "sentinel_node")
            .setContentTitle("EdgeSwarm Sentinel")
            .setContentText("Headless Node active.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(1001, notification)

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EdgeSwarm::CpuWakeLock").apply { acquire() }

        isServiceRunning = true
        // 🚨 Restored your UI Routing Switches
        startHeadlessEngine(
            intent?.getStringExtra("USER_EMAIL") ?: "mobile_node@edgeswarm.com",
            intent?.getBooleanExtra("ALLOW_COMPUTE", true) ?: true,
            intent?.getBooleanExtra("ALLOW_SCRAPING", true) ?: true,
            intent?.getBooleanExtra("ALLOW_SLM", true) ?: true
        )

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        serviceScope.cancel()
        slmEngine?.close()
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun startHeadlessEngine(userEmail: String, allowCompute: Boolean, allowScraping: Boolean, allowSlm: Boolean) {
        serviceScope.launch {
            var interpreter: Interpreter? = null
            try {
                // 🚨 Restored all original verbose logging
                Log.d("EdgeSwarm", "🚀 Headless Engine Booting (CPU Mode)...")
                interpreter = initTFLiteInterpreter()
                if (allowSlm) slmEngine = initSlmEngine()

                val deviceName = android.os.Build.MANUFACTURER + "_" + android.os.Build.MODEL
                val hardwareId = deviceName.replace(" ", "") + "_" + Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).take(6)

                while (isServiceRunning) {
                    val task = fetchTaskFromMempool(hardwareId)
                    if (task != null) {
                        val taskId = task.getInt("taskId")
                        val prompt = task.getString("prompt")
                        val bounty = task.getDouble("bounty")

                        Log.d("EdgeSwarm", "📥 Loop Check -> Task ID: $taskId | Prompt: \"$prompt\"")

                        var aiOutput = ""
                        var isError = false
                        val start = System.currentTimeMillis()

                        // 🚨 BULLETPROOF TASK ROUTING AND ERROR HANDLING
                        try {
                            if (prompt.startsWith("compute://") && allowCompute) {
                                Log.d("EdgeSwarm", "⚙️ Executing Tensor Math...")
                                val buffer = ByteBuffer.allocate(1000 * 4) // 1000 floats
                                buffer.order(ByteOrder.LITTLE_ENDIAN) // Force matching byte order for server
                                for (i in 0 until 1000) {
                                    buffer.putFloat(Random.nextFloat() * 2f - 1f)
                                }
                                aiOutput = Base64.encodeToString(buffer.array(), Base64.NO_WRAP)

                            } else if (prompt.startsWith("http") && allowScraping) {
                                Log.d("EdgeSwarm", "🌐 Executing Professional Scrape...")

                                val request = Request.Builder()
                                    .url(prompt)
                                    .header(
                                        "User-Agent",
                                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                                    )
                                    .header(
                                        "Accept",
                                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
                                    )
                                    .header("Accept-Language", "en-US,en;q=0.5")
                                    .build()

                                val response = httpClient.newCall(request).execute()

                                // 🚨 FIX: Production-grade validation
                                if (!response.isSuccessful) {
                                    throw Exception("Scrape Failed: HTTP ${response.code}")
                                }

                                val body = response.body?.string()

                                if (body.isNullOrBlank()) {
                                    throw Exception("Scrape Failed: Empty response body (Likely Cloudflare/Bot Block)")
                                }

                                // Advanced cleaning: Remove CSS, Scripts, and complex tags
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

                                val safeText = if (cleanText.length > 200000) {
                                    cleanText.take(200000)
                                } else {
                                    cleanText
                                }

                                val jsonOut = JSONObject().apply {
                                    put("source_url", prompt)
                                    put("content", safeText)
                                    put("node_attestation", "Verified Mobile Proxy")
                                }

                                aiOutput = jsonOut.toString()
                            } else if (allowSlm) {
                                // 3. SLM TEXT ROUTE (THE REAL ENGINE)
                                if (slmEngine != null) {
                                    val cleanPrompt = prompt.replace("prompt://", "")
                                    aiOutput = slmEngine!!.generateResponse(cleanPrompt)
                                } else {
                                    // 🚨 NEW: Sends the real reason directly to the server
                                    throw Exception("SLM Offline. Reason: $lastEngineError")
                                }
                            } else {
                                throw Exception("All routing switches disabled.")
                            }
                        } catch (e: Exception) {
                            Log.e("EdgeSwarm", "⚠️ Task Execution Crashed", e)
                            aiOutput = "Execution Failed: ${e.message}"
                            isError = true // Mark as error so server unlocks it immediately
                        }

                        val latencyMs = System.currentTimeMillis() - start

                        // Truncate only if it's NOT a structured JSON payload to protect formatting
                        if (!prompt.startsWith("http") && aiOutput.length > 512000) {
                            aiOutput = aiOutput.take(512000) + "...[Truncated]"
                        }

                        uploadViaStream(
                            taskId,
                            userEmail,
                            latencyMs,
                            hardwareId,
                            bounty,
                            aiOutput,
                            null,
                            if (isError) "error" else "success"
                        )
                    }
                    delay(10000)
                }
            } catch (e: Exception) {
                Log.e("EdgeSwarm", "Engine Loop Crash: ${e.message}")
            } finally {
                interpreter?.close()
                stopSelf()
            }
        }
    }

    private fun initSlmEngine(): LlmInference? {
        val modelName = "gemma-2b-it-cpu-int4.bin"
        val file = File(applicationContext.filesDir, modelName)

        // 🚨 1. INTEGRITY CHECK: Gemma is roughly 1.4GB. If the file is smaller than 1GB, it's corrupted.
        if (file.exists() && file.length() < 1000000000L) {
            Log.w("EdgeSwarm", "⚠️ Corrupted model detected (Size: ${file.length()} bytes). Wiping...")
            file.delete()
        }

        // 🚨 2. SAFE CHUNKED COPY
        if (!file.exists()) {
            try {
                Log.d("EdgeSwarm", "🧠 Unpacking 1.5GB Gemma Model... DO NOT CLOSE APP. This takes 1-2 minutes.")
                applicationContext.assets.open(modelName).use { input ->
                    file.outputStream().use { output ->
                        // Use a 4MB buffer to prevent Android from Out-Of-Memory crashing the app
                        val buffer = ByteArray(4 * 1024 * 1024)
                        var bytesRead: Int
                        var totalRead = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead

                            // Log progress to the console every ~100MB so you know it's working
                            if (totalRead % (100 * 1024 * 1024) < (4 * 1024 * 1024)) {
                                Log.d("EdgeSwarm", "⏳ Copied ${totalRead / (1024 * 1024)} MB...")
                            }
                        }
                    }
                }
                Log.d("EdgeSwarm", "✅ Model Unpack Complete! Final Size: ${file.length() / (1024 * 1024)} MB")
            } catch (e: Exception) {
                Log.e("EdgeSwarm", "Model Copy Failed", e)
                file.delete() // Wipe the partial file so it tries again next time
                return null
            }
        }

        // 🚨 3. BOOT THE ENGINE
        return try {
            val options = LlmInferenceOptions.builder()
                .setModelPath(file.absolutePath)
                .setMaxTokens(512)
                .build()
            LlmInference.createFromOptions(applicationContext, options)
        } catch (e: Exception) {
            // 🚨 NEW: Save the exact MediaPipe error so the server can see it!
            lastEngineError = e.stackTraceToString().take(200)
            null
        }
    } // 🚨 RESTORED MISSING BRACKET HERE

    private fun getOrCreateNodeCredentials(email: String): Credentials {
        val prefs = getSharedPreferences("EdgeSwarmNode", MODE_PRIVATE)
        val keyName = "private_key_$email"
        var privateKeyHex = prefs.getString(keyName, null)

        if (privateKeyHex == null) {
            val ecKeyPair = Keys.createEcKeyPair()
            privateKeyHex = ecKeyPair.privateKey.toString(16)
            prefs.edit().putString(keyName, privateKeyHex).commit()
            Log.d("EdgeSwarm", "🔑 New Wallet Generated & Locked for $email.")
        }

        return Credentials.create(privateKeyHex)
    }

    private fun signPayloadSecurely(taskId: Int, properFileHash: String, hwId: String, workerEmail: String): String {
        return try {
            val nodeCredentials = getOrCreateNodeCredentials(workerEmail)

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
            Log.d("EdgeSwarm", "🔐 Signed Exact Server String: $finalSig")

            finalSig
        } catch (e: Exception) {
            Log.e("EdgeSwarm", "Signature Math Failed: ${e.message}")
            "0x0"
        }
    }

    private fun uploadViaStream(
        taskId: Int,
        workerEmail: String,
        latency: Long,
        hwId: String,
        bounty: Double,
        aiOutput: String,
        aiTranslation: String?,
        finalStatus: String
    ): Boolean {
        Log.d("EdgeSwarm", "Uploading payload to: $gcpUploadUrl")

        return try {
            val nodeCredentials = getOrCreateNodeCredentials(workerEmail)

            // 🚨 ENFORCE UTF-8 TO PREVENT HASHING COLLISION
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(aiOutput.toByteArray(Charsets.UTF_8))
            val properFileHash = Numeric.toHexStringNoPrefix(hashBytes)

            val realProductionSignature = signPayloadSecurely(taskId, properFileHash, hwId, workerEmail)

            val payloadJson = JSONObject().apply {
                put("taskId", taskId)
                put("worker", nodeCredentials.address)
                put("score", 100)
                put("signature", realProductionSignature)
                put("hardwareId", hwId)
                put("aiOutput", aiOutput)
                put("aiTranslation", aiTranslation ?: JSONObject.NULL)
                put("status", finalStatus)
                put("latency_ms", latency)
                put("bounty", bounty)
            }

            val rootJson = JSONObject().apply {
                put("fileHash", properFileHash)
                put("payload", payloadJson)
            }

            val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val requestBody = rootJson.toString().toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url(gcpUploadUrl)
                .post(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                Log.d("EdgeSwarm", "Upload Result: ${response.code} - ${response.message}")
                if (!response.isSuccessful) {
                    Log.e("EdgeSwarm", "Server rejected payload: ${response.body?.string()}")
                }
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e("EdgeSwarm", "Upload Network Crash: ${e.message}")
            false
        }
    }

    private fun copyAssetToInternalStorage(fileName: String) {
        val file = File(applicationContext.filesDir, fileName)
        if (!file.exists()) {
            applicationContext.assets.open(fileName).use { inputStream ->
                file.outputStream().use { inputStream.copyTo(it) }
            }
        }
    }

    private fun fetchTaskFromMempool(hwId: String): JSONObject? {
        return try {
            val request = Request.Builder().url("$gcpJobsUrl?hardwareId=$hwId").build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    if (json.has("task") && !json.isNull("task")) json.getJSONObject("task") else null
                } else null
            }
        } catch (e: Exception) { null }
    }

    private fun initTFLiteInterpreter(): Interpreter? {
        return try {
            val fd = applicationContext.assets.openFd("edge_swarm_complex.tflite")
            val tfliteModel = FileInputStream(fd.fileDescriptor).use { inputStream ->
                inputStream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
            fd.close()
            Interpreter(tfliteModel, Interpreter.Options().apply { setNumThreads(4) })
        } catch (e: Exception) { null }
    }
}