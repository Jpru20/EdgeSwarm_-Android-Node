package com.edgeswarm.node

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ANDROID_LEVEL2_RUNTIME_SELF_TEST_V1

data class AndroidLevel2SelfTestResult(
    val modelId: String,
    val capability: String,
    val backend: AndroidLevel2Backend,
    val modelFilePath: String,
    val responseText: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val timeToFirstTokenMs: Long,
    val decodeTokensPerSecond: Double
)

class AndroidLevel2SelfTestCoordinator(
    context: Context
) {
    private val appContext = context.applicationContext

    private val installer =
        AndroidLevel2ModelInstaller(appContext)

    private val nativeLibraryDir =
        appContext.applicationInfo.nativeLibraryDir

    suspend fun initializeAndRun(
        runtime: AndroidLevel2Runtime
    ): AndroidLevel2SelfTestResult =
        withContext(Dispatchers.IO) {
            val recommendation =
                installer.requestRecommendation(
                    confirmDownload = true
                )

            check(recommendation.qualified) {
                recommendation.downloadBlockedReason
                    ?: "This device is not qualified for Android Level 2."
            }

            val descriptor =
                recommendation.model
                    ?: error(
                        "The backend did not return the Android Level 2 model."
                    )

            val modelFile =
                installer.verifyInstalledModel(descriptor)
                    ?: error(
                        "The verified Android Level 2 model is not installed."
                    )

            val backends = listOf(
                AndroidLevel2Backend.NPU,
                AndroidLevel2Backend.GPU,
                AndroidLevel2Backend.CPU
            )

            var lastFailure: Throwable? = null

            for (backend in backends) {
                try {
                    Log.i(
                        "EdgeSwarm",
                        "Level 2 self-test attempting backend=" +
                            backend.telemetryName +
                            " nativeLibraryDir=" +
                            if (
                                backend ==
                                    AndroidLevel2Backend.NPU
                            ) {
                                nativeLibraryDir
                            } else {
                                "not-required"
                            }
                    )

                    runtime.initialize(
                        modelFile = modelFile,
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
                                nativeLibraryDir
                            } else {
                                null
                            }
                    )

                    val inference = runtime.generate(
                        "Reply with exactly this text and nothing else: " +
                            "EDGESWARM_LEVEL2_READY"
                    )

                    check(
                        inference.text.contains(
                            "EDGESWARM_LEVEL2_READY",
                            ignoreCase = true
                        )
                    ) {
                        "Level 2 self-test returned an unexpected response: " +
                            inference.text.take(200)
                    }

                    return@withContext AndroidLevel2SelfTestResult(
                        modelId = descriptor.id,
                        capability = descriptor.capability,
                        backend = backend,
                        modelFilePath = modelFile.absolutePath,
                        responseText = inference.text,
                        inputTokens = inference.inputTokens,
                        outputTokens = inference.outputTokens,
                        timeToFirstTokenMs =
                            inference.timeToFirstTokenMs,
                        decodeTokensPerSecond =
                            inference.decodeTokensPerSecond
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    lastFailure = error

                    Log.w(
                        "EdgeSwarm",
                        "Level 2 self-test backend=" +
                            backend.telemetryName +
                            " failed: " +
                            (
                                error.message
                                    ?: error.javaClass.simpleName
                            ),
                        error
                    )
                }
            }

            throw IllegalStateException(
                "Android Level 2 failed NPU, GPU, and CPU self-tests.",
                lastFailure
            )
        }
}
