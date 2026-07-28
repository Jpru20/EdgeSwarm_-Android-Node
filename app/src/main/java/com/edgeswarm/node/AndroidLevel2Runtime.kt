package com.edgeswarm.node

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToLong

enum class AndroidLevel2Backend(
    val telemetryName: String
) {
    CPU("cpu"),
    GPU("gpu"),
    NPU("npu")
}

enum class AndroidLevel2Status {
    NOT_INSTALLED,
    LOADING,
    READY,
    ERROR,
    CLOSED
}

data class AndroidLevel2InferenceResult(
    val text: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val totalConversationTokens: Int,
    val timeToFirstTokenMs: Long,
    val decodeTokensPerSecond: Double
)

/**
 * ANDROID_LEVEL2_LITERT_LM_RUNTIME_V1
 *
 * Owns one initialized LiteRT-LM engine.
 *
 * A fresh Conversation is created for every EdgeSwarm task so prompts and
 * responses cannot leak between independent client workloads.
 */
@OptIn(ExperimentalApi::class)
class AndroidLevel2Runtime(
    private val cacheDir: File
) : AutoCloseable {

    private val runtimeLock = ReentrantLock()

    private var engine: Engine? = null

    @Volatile
    var status: AndroidLevel2Status = AndroidLevel2Status.NOT_INSTALLED
        private set

    @Volatile
    var activeBackend: AndroidLevel2Backend? = null
        private set

    @Volatile
    var activeModelPath: String? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    val isReady: Boolean
        get() = status == AndroidLevel2Status.READY &&
            engine?.isInitialized() == true

    fun initialize(
        modelFile: File,
        backend: AndroidLevel2Backend,
        maxNumTokens: Int = 2048,
        nativeLibraryDir: String? = null
    ) {
        runtimeLock.withLock {
            closeEngineLocked()

            require(modelFile.isFile) {
                "Level 2 model file does not exist: ${modelFile.absolutePath}"
            }

            require(modelFile.length() > 0L) {
                "Level 2 model file is empty."
            }

            require(maxNumTokens > 0) {
                "maxNumTokens must be greater than zero."
            }

            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                throw IllegalStateException(
                    "Unable to create Level 2 cache directory: " +
                        cacheDir.absolutePath
                )
            }

            status = AndroidLevel2Status.LOADING
            lastError = null

            try {
                val selectedBackend = when (backend) {
                    AndroidLevel2Backend.CPU -> Backend.CPU()
                    AndroidLevel2Backend.GPU -> Backend.GPU()

                    AndroidLevel2Backend.NPU -> {
                        val resolvedNativeLibraryDir =
                            nativeLibraryDir
                                ?.trim()
                                ?.takeIf(String::isNotEmpty)
                                ?: throw IllegalArgumentException(
                                    "The Android NPU backend requires " +
                                        "applicationInfo.nativeLibraryDir."
                                )

                        Backend.NPU(
                            nativeLibraryDir =
                                resolvedNativeLibraryDir
                        )
                    }
                }

                val newEngine = Engine(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = selectedBackend,
                        maxNumTokens = maxNumTokens,
                        cacheDir = cacheDir.absolutePath
                    )
                )

                newEngine.initialize()

                check(newEngine.isInitialized()) {
                    "LiteRT-LM engine did not report initialized state."
                }

                engine = newEngine
                activeBackend = backend
                activeModelPath = modelFile.absolutePath
                status = AndroidLevel2Status.READY
            } catch (error: Throwable) {
                closeEngineLocked()
                lastError =
                    error.message ?: error.javaClass.simpleName
                status = AndroidLevel2Status.ERROR
                throw error
            }
        }
    }

    fun generate(prompt: String): AndroidLevel2InferenceResult {
        require(prompt.isNotBlank()) {
            "Neural inference prompt cannot be blank."
        }

        return runtimeLock.withLock {
            val activeEngine = engine

            check(
                status == AndroidLevel2Status.READY &&
                    activeEngine?.isInitialized() == true
            ) {
                "Android Level 2 runtime is not ready."
            }

            activeEngine
                .createConversation(ConversationConfig())
                .use { conversation ->
                    val response = conversation.sendMessage(prompt)

                    val generatedText = response
                        .contents
                        .contents
                        .filterIsInstance<Content.Text>()
                        .joinToString(separator = "") { content ->
                            content.text
                        }
                        .trim()

                    check(generatedText.isNotBlank()) {
                        "LiteRT-LM returned an empty text response."
                    }

                    val benchmark =
                        runCatching {
                            conversation.getBenchmarkInfo()
                        }.getOrNull()

                    AndroidLevel2InferenceResult(
                        text = generatedText,
                        inputTokens =
                            benchmark?.lastPrefillTokenCount ?: 0,
                        outputTokens =
                            benchmark?.lastDecodeTokenCount ?: 0,
                        totalConversationTokens =
                            (benchmark?.lastPrefillTokenCount ?: 0) +
                                (benchmark?.lastDecodeTokenCount ?: 0),
                        timeToFirstTokenMs =
                            benchmark
                                ?.timeToFirstTokenInSecond
                                ?.times(1000.0)
                                ?.roundToLong()
                                ?: 0L,
                        decodeTokensPerSecond =
                            benchmark
                                ?.lastDecodeTokensPerSecond
                                ?: 0.0
                    )
                }
        }
    }

    override fun close() {
        runtimeLock.withLock {
            closeEngineLocked()
            status = AndroidLevel2Status.CLOSED
        }
    }

    private fun closeEngineLocked() {
        runCatching {
            engine?.close()
        }

        engine = null
        activeBackend = null
        activeModelPath = null
    }
}
