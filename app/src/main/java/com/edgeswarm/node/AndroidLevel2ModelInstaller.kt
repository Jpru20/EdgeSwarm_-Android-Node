package com.edgeswarm.node

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

// ANDROID_LEVEL2_MODEL_INSTALLER_V1

data class AndroidLevel2ModelDescriptor(
    val id: String,
    val name: String,
    val capability: String,
    val level: Int,
    val runtime: String,
    val modelFormat: String,
    val downloadReady: Boolean,
    val downloadUrl: String,
    val sha256: String,
    val filename: String,
    val sizeBytes: Long,
    val requiresSelfTest: Boolean
)

data class AndroidLevel2Recommendation(
    val recommendationVersion: String,
    val qualified: Boolean,
    val shouldDownload: Boolean,
    val recommendationReason: String,
    val downloadBlockedReason: String?,
    val model: AndroidLevel2ModelDescriptor?
)

enum class AndroidLevel2InstallStage {
    CHECKING,
    DOWNLOADING,
    VERIFYING,
    INSTALLED
}

data class AndroidLevel2InstallProgress(
    val stage: AndroidLevel2InstallStage,
    val downloadedBytes: Long,
    val totalBytes: Long
) {
    val percent: Int
        get() = when {
            totalBytes <= 0L -> 0
            downloadedBytes >= totalBytes -> 100
            else -> (
                downloadedBytes.toDouble() /
                    totalBytes.toDouble() *
                    100.0
                ).toInt().coerceIn(0, 100)
        }
}

data class AndroidLevel2InstalledModel(
    val descriptor: AndroidLevel2ModelDescriptor,
    val file: File
)

class AndroidLevel2ModelInstaller(
    context: Context,
    private val httpClient: OkHttpClient = createDefaultHttpClient()
) {
    private val appContext = context.applicationContext

    suspend fun requestRecommendation(
        confirmDownload: Boolean
    ): AndroidLevel2Recommendation = withContext(Dispatchers.IO) {
        val profile = buildDeviceProfile(confirmDownload)

        val request = Request.Builder()
            .url(
                "${EdgeSwarmConfig.apiBaseUrl}" +
                    "/node/model-recommendation"
            )
            .header("Accept", "application/json")
            .post(
                profile.toString().toRequestBody(
                    JSON_MEDIA_TYPE
                )
            )
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()

            check(response.isSuccessful) {
                "Level 2 recommendation failed with HTTP " +
                    "${response.code}: ${responseText.take(500)}"
            }

            parseRecommendation(JSONObject(responseText))
        }
    }

    suspend fun downloadAndInstall(
        descriptor: AndroidLevel2ModelDescriptor,
        onProgress: (AndroidLevel2InstallProgress) -> Unit = {}
    ): AndroidLevel2InstalledModel = withContext(Dispatchers.IO) {
        validateDescriptor(descriptor)

        val modelDirectory = File(
            appContext.filesDir,
            MODEL_DIRECTORY_NAME
        )

        check(
            modelDirectory.exists() ||
                modelDirectory.mkdirs()
        ) {
            "Unable to create model directory: " +
                modelDirectory.absolutePath
        }

        val installedFile = File(
            modelDirectory,
            descriptor.filename
        )

        val partialFile = File(
            modelDirectory,
            "${descriptor.filename}.partial"
        )

        onProgress(
            AndroidLevel2InstallProgress(
                stage = AndroidLevel2InstallStage.CHECKING,
                downloadedBytes = installedFile.length(),
                totalBytes = descriptor.sizeBytes
            )
        )

        if (
            installedFile.isFile &&
            installedFile.length() == descriptor.sizeBytes &&
            calculateSha256(installedFile)
                .equals(descriptor.sha256, ignoreCase = true)
        ) {
            onProgress(
                AndroidLevel2InstallProgress(
                    stage = AndroidLevel2InstallStage.INSTALLED,
                    downloadedBytes = descriptor.sizeBytes,
                    totalBytes = descriptor.sizeBytes
                )
            )

            return@withContext AndroidLevel2InstalledModel(
                descriptor = descriptor,
                file = installedFile
            )
        }

        if (installedFile.exists() && !installedFile.delete()) {
            error(
                "Unable to remove invalid installed model: " +
                    installedFile.absolutePath
            )
        }

        if (
            partialFile.exists() &&
            partialFile.length() > descriptor.sizeBytes
        ) {
            check(partialFile.delete()) {
                "Unable to reset oversized partial model download."
            }
        }

        var resumeFrom = partialFile
            .takeIf(File::isFile)
            ?.length()
            ?: 0L

        val requestBuilder = Request.Builder()
            .url(descriptor.downloadUrl)
            .header("Accept", "application/octet-stream")

        if (resumeFrom > 0L) {
            requestBuilder.header(
                "Range",
                "bytes=$resumeFrom-"
            )
        }

        httpClient.newCall(requestBuilder.build())
            .execute()
            .use { response ->
                check(
                    response.code == 200 ||
                        response.code == 206
                ) {
                    "Model download failed with HTTP " +
                        "${response.code}"
                }

                val append =
                    resumeFrom > 0L &&
                        response.code == 206

                if (!append) {
                    resumeFrom = 0L

                    if (
                        partialFile.exists() &&
                        !partialFile.delete()
                    ) {
                        error(
                            "Unable to restart partial model download."
                        )
                    }
                }

                val body = response.body
                    ?: error("Model download returned an empty body.")

                var downloadedBytes = resumeFrom
                var lastProgressBytes = resumeFrom

                FileOutputStream(partialFile, append).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(
                            DOWNLOAD_BUFFER_BYTES
                        )

                        while (true) {
                            coroutineContext.ensureActive()

                            val read = input.read(buffer)

                            if (read < 0) {
                                break
                            }

                            output.write(buffer, 0, read)
                            downloadedBytes += read

                            if (
                                downloadedBytes - lastProgressBytes >=
                                PROGRESS_INTERVAL_BYTES
                            ) {
                                lastProgressBytes = downloadedBytes

                                onProgress(
                                    AndroidLevel2InstallProgress(
                                        stage =
                                            AndroidLevel2InstallStage.DOWNLOADING,
                                        downloadedBytes =
                                            downloadedBytes,
                                        totalBytes =
                                            descriptor.sizeBytes
                                    )
                                )
                            }
                        }
                    }

                    output.fd.sync()
                }
            }

        check(partialFile.length() == descriptor.sizeBytes) {
            "Downloaded model size mismatch. Expected " +
                "${descriptor.sizeBytes}, received " +
                "${partialFile.length()}."
        }

        onProgress(
            AndroidLevel2InstallProgress(
                stage = AndroidLevel2InstallStage.VERIFYING,
                downloadedBytes = descriptor.sizeBytes,
                totalBytes = descriptor.sizeBytes
            )
        )

        val downloadedHash = calculateSha256(partialFile)

        check(
            downloadedHash.equals(
                descriptor.sha256,
                ignoreCase = true
            )
        ) {
            partialFile.delete()

            "Downloaded model SHA-256 mismatch. Expected " +
                "${descriptor.sha256.lowercase(Locale.US)}, " +
                "received $downloadedHash."
        }

        if (installedFile.exists()) {
            check(installedFile.delete()) {
                "Unable to replace the existing model."
            }
        }

        check(partialFile.renameTo(installedFile)) {
            "Unable to atomically install the verified model."
        }

        File(
            modelDirectory,
            "${descriptor.filename}.sha256"
        ).writeText(
            descriptor.sha256.lowercase(Locale.US),
            Charsets.UTF_8
        )

        onProgress(
            AndroidLevel2InstallProgress(
                stage = AndroidLevel2InstallStage.INSTALLED,
                downloadedBytes = descriptor.sizeBytes,
                totalBytes = descriptor.sizeBytes
            )
        )

        AndroidLevel2InstalledModel(
            descriptor = descriptor,
            file = installedFile
        )
    }

    suspend fun verifyInstalledModel(
        descriptor: AndroidLevel2ModelDescriptor
    ): File? = withContext(Dispatchers.IO) {
        val file = File(
            File(
                appContext.filesDir,
                MODEL_DIRECTORY_NAME
            ),
            descriptor.filename
        )

        if (
            !file.isFile ||
            file.length() != descriptor.sizeBytes
        ) {
            return@withContext null
        }

        if (
            !calculateSha256(file).equals(
                descriptor.sha256,
                ignoreCase = true
            )
        ) {
            return@withContext null
        }

        file
    }

    fun removeInstalledModel(filename: String): Boolean {
        val modelDirectory = File(
            appContext.filesDir,
            MODEL_DIRECTORY_NAME
        )

        val installedFile = File(modelDirectory, filename)
        val partialFile = File(
            modelDirectory,
            "$filename.partial"
        )
        val checksumFile = File(
            modelDirectory,
            "$filename.sha256"
        )

        val installedRemoved =
            !installedFile.exists() ||
                installedFile.delete()

        val partialRemoved =
            !partialFile.exists() ||
                partialFile.delete()

        val checksumRemoved =
            !checksumFile.exists() ||
                checksumFile.delete()

        return installedRemoved &&
            partialRemoved &&
            checksumRemoved
    }

    private fun buildDeviceProfile(
        confirmDownload: Boolean
    ): JSONObject {
        val activityManager = appContext.getSystemService(
            Context.ACTIVITY_SERVICE
        ) as ActivityManager

        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val storage = StatFs(
            appContext.filesDir.absolutePath
        )

        return JSONObject()
            .put("nodeType", "android-node")
            .put("platform", "android")
            .put(
                "cpuAbi",
                Build.SUPPORTED_ABIS.firstOrNull()
                    ?: Build.CPU_ABI
            )
            .put(
                "androidVersion",
                Build.VERSION.RELEASE
            )
            .put("deviceModel", Build.MODEL)
            .put(
                "ramGb",
                memoryInfo.totalMem.toDouble() /
                    BYTES_PER_GIB
            )
            .put(
                "availableRamGb",
                memoryInfo.availMem.toDouble() /
                    BYTES_PER_GIB
            )
            .put(
                "diskFreeGb",
                storage.availableBytes.toDouble() /
                    BYTES_PER_GIB
            )
            .put(
                "cpuCores",
                Runtime.getRuntime()
                    .availableProcessors()
            )
            .put(
                "confirmAndroidLevel2Download",
                confirmDownload
            )
    }

    private fun parseRecommendation(
        root: JSONObject
    ): AndroidLevel2Recommendation {
        val profile = root.optJSONObject("nodeProfile")
        val modelJson = root.optJSONObject("recommendedModel")

        val model = modelJson?.let {
            AndroidLevel2ModelDescriptor(
                id = it.optString("id"),
                name = it.optString("name"),
                capability = it.optString("capability"),
                level = it.optInt("level"),
                runtime = it.optString("runtime"),
                modelFormat = it.optString("modelFormat"),
                downloadReady =
                    it.optBoolean("downloadReady"),
                downloadUrl =
                    it.optString("downloadUrl"),
                sha256 =
                    it.optString("sha256"),
                filename =
                    it.optString("filename"),
                sizeBytes =
                    it.optLong("sizeBytes"),
                requiresSelfTest =
                    it.optBoolean("requiresSelfTest")
            )
        }

        val qualified =
            model?.id == EXPECTED_MODEL_ID &&
                model.level >= 2

        return AndroidLevel2Recommendation(
            recommendationVersion =
                root.optString("recommendationVersion"),
            qualified = qualified,
            shouldDownload =
                root.optBoolean("shouldDownload"),
            recommendationReason =
                profile?.optString(
                    "recommendationReason"
                ).orEmpty(),
            downloadBlockedReason =
                modelJson
                    ?.optString(
                        "downloadBlockedReason"
                    )
                    ?.takeIf(String::isNotBlank),
            model = model
        )
    }

    private fun validateDescriptor(
        descriptor: AndroidLevel2ModelDescriptor
    ) {
        require(descriptor.id == EXPECTED_MODEL_ID) {
            "Unexpected Android Level 2 model: " +
                descriptor.id
        }

        require(
            descriptor.runtime.equals(
                EXPECTED_RUNTIME,
                ignoreCase = true
            )
        ) {
            "Unsupported Android Level 2 runtime: " +
                descriptor.runtime
        }

        require(descriptor.downloadReady) {
            "Model download is not enabled."
        }

        require(descriptor.downloadUrl.startsWith("https://")) {
            "Model download URL must use HTTPS."
        }

        require(descriptor.sha256.matches(SHA256_PATTERN)) {
            "Model SHA-256 is invalid."
        }

        require(descriptor.filename.isNotBlank()) {
            "Model filename is missing."
        }

        require(
            !descriptor.filename.contains("/") &&
                !descriptor.filename.contains("\\")
        ) {
            "Model filename contains an invalid path."
        }

        require(descriptor.sizeBytes > 0L) {
            "Model size is invalid."
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(HASH_BUFFER_BYTES)

        file.inputStream().buffered().use { input ->
            while (true) {
                val read = input.read(buffer)

                if (read < 0) {
                    break
                }

                digest.update(buffer, 0, read)
            }
        }

        return digest.digest().joinToString("") {
            "%02x".format(Locale.US, it)
        }
    }

    companion object {
        private const val EXPECTED_MODEL_ID =
            "gemma4:e2b"

        private const val EXPECTED_RUNTIME =
            "litert-lm"

        private const val MODEL_DIRECTORY_NAME =
            "level2_models"

        private const val DOWNLOAD_BUFFER_BYTES =
            1024 * 1024

        private const val HASH_BUFFER_BYTES =
            1024 * 1024

        private const val PROGRESS_INTERVAL_BYTES =
            4L * 1024L * 1024L

        private const val BYTES_PER_GIB =
            1024.0 * 1024.0 * 1024.0

        private val JSON_MEDIA_TYPE =
            "application/json; charset=utf-8".toMediaType()

        private val SHA256_PATTERN =
            Regex("^[A-Fa-f0-9]{64}$")

        private fun createDefaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(
                    30,
                    TimeUnit.SECONDS
                )
                .readTimeout(
                    2,
                    TimeUnit.MINUTES
                )
                .writeTimeout(
                    2,
                    TimeUnit.MINUTES
                )
                .callTimeout(
                    0,
                    TimeUnit.MILLISECONDS
                )
                .retryOnConnectionFailure(true)
                .build()
    }
}