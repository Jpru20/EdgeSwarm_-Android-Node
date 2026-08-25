package com.edgeswarm.node

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

// ANDROID_NEURAL_CAPACITY_CERTIFICATION_V1
// Same six real-world workload classes used by the unified neural
// certification policy. The recommended backend capability is bound at
// runtime rather than hardcoded to a device architecture or model tier.

data class AndroidNeuralCapacityCertificationResult(
    val modelId: String,
    val modelSha256: String,
    val capability: String,
    val backend: AndroidLevel2Backend,
    val modelFilePath: String,
    val certifiedConcurrency: Int,
    val baselineMedianTaskMs: Long,
    val baselineAggregateTokensPerSecond: Double,
    val certifiedMedianTaskMs: Long,
    val certifiedAggregateTokensPerSecond: Double,
    val qualityPassRate: Double,
    val certificateFilePath: String
)

private data class AndroidCertificationWorkload(
    val id: String,
    val prompt: String,
    val requiredKeys: Set<String>,
    val expectedValues: Map<String, String> = emptyMap(),
    val requiredTerms: Set<String> = emptySet()
)

private data class AndroidCertificationTaskResult(
    val workloadId: String,
    val valid: Boolean,
    val wallMs: Long,
    val outputTokens: Int,
    val decodeTokensPerSecond: Double
)

private data class AndroidCertificationSample(
    val concurrency: Int,
    val wallMs: Long,
    val medianTaskMs: Long,
    val aggregateTokensPerSecond: Double,
    val qualityPassRate: Double,
    val results: List<AndroidCertificationTaskResult>,
    val thermalConstrained: Boolean
)

class AndroidNeuralCapacityCertificationCoordinator(
    context: Context
) {
    private val appContext = context.applicationContext
    private val installer = AndroidLevel2ModelInstaller(appContext)
    private val nativeLibraryDir =
        appContext.applicationInfo.nativeLibraryDir
    private val powerManager =
        appContext.getSystemService(PowerManager::class.java)

    suspend fun initializeAndRun(
        primaryRuntime: AndroidLevel2Runtime
    ): AndroidNeuralCapacityCertificationResult =
        withContext(Dispatchers.IO) {
            val recommendation =
                installer.requestRecommendation(confirmDownload = true)

            check(recommendation.qualified) {
                recommendation.downloadBlockedReason
                    ?: "Device is not qualified for neural certification."
            }

            val descriptor =
                recommendation.model
                    ?: error(
                        "Backend did not return a neural model recommendation."
                    )

            check(
                descriptor.capability.startsWith(
                    "Neural-Inference-",
                    ignoreCase = true
                )
            ) {
                "Recommended model is not a neural capability: " +
                    descriptor.capability
            }

            val modelFile =
                installer.verifyInstalledModel(descriptor)
                    ?: error(
                        "Verified recommended neural model is not installed."
                    )

            val workloads = certificationWorkloads()
            var lastFailure: Throwable? = null

            for (
                backend in listOf(
                    AndroidLevel2Backend.NPU,
                    AndroidLevel2Backend.GPU,
                    AndroidLevel2Backend.CPU
                )
            ) {
                try {
                    val baseline =
                        runBaseline(
                            runtime = primaryRuntime,
                            modelFile = modelFile,
                            backend = backend,
                            workloads = workloads
                        )

                    check(baseline.qualityPassRate >= 1.0) {
                        "baseline_quality_failed"
                    }

                    check(!baseline.thermalConstrained) {
                        "baseline_thermal_constraint"
                    }

                    val concurrencyTwo =
                        runCatching {
                            runConcurrencyTwo(
                                modelFile = modelFile,
                                backend = backend,
                                workloads = workloads
                            )
                        }.getOrNull()

                    val c2Accepted =
                        concurrencyTwo?.let { sample ->
                            val latencyLimit =
                                max(
                                    baseline.medianTaskMs * 1.80,
                                    baseline.medianTaskMs + 750.0
                                )

                            val throughputPass =
                                baseline.aggregateTokensPerSecond <= 0.0 ||
                                    sample.aggregateTokensPerSecond >=
                                    baseline.aggregateTokensPerSecond * 0.90

                            sample.qualityPassRate >= 1.0 &&
                                !sample.thermalConstrained &&
                                sample.medianTaskMs.toDouble() <=
                                    latencyLimit &&
                                throughputPass
                        } ?: false

                    val certified =
                        if (c2Accepted) {
                            requireNotNull(concurrencyTwo)
                        } else {
                            baseline
                        }

                    val result =
                        AndroidNeuralCapacityCertificationResult(
                            modelId = descriptor.id,
                            modelSha256 =
                                descriptor.sha256.lowercase(),
                            capability = descriptor.capability,
                            backend = backend,
                            modelFilePath =
                                modelFile.absolutePath,
                            certifiedConcurrency =
                                certified.concurrency,
                            baselineMedianTaskMs =
                                baseline.medianTaskMs,
                            baselineAggregateTokensPerSecond =
                                baseline.aggregateTokensPerSecond,
                            certifiedMedianTaskMs =
                                certified.medianTaskMs,
                            certifiedAggregateTokensPerSecond =
                                certified.aggregateTokensPerSecond,
                            qualityPassRate =
                                certified.qualityPassRate,
                            certificateFilePath = ""
                        )

                    val certificate =
                        persistCertificate(
                            result = result,
                            concurrencyTwo = concurrencyTwo,
                            c2Accepted = c2Accepted
                        )

                    return@withContext result.copy(
                        certificateFilePath =
                            certificate.absolutePath
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    lastFailure = error
                    runCatching { primaryRuntime.close() }
                }
            }

            throw IllegalStateException(
                "Android neural certification failed NPU, GPU, and CPU.",
                lastFailure
            )
        }

    private fun initializeRuntime(
        runtime: AndroidLevel2Runtime,
        modelFile: File,
        backend: AndroidLevel2Backend
    ) {
        runtime.initialize(
            modelFile = modelFile,
            backend = backend,
            maxNumTokens =
                if (backend == AndroidLevel2Backend.NPU) {
                    1024
                } else {
                    2048
                },
            nativeLibraryDir =
                if (backend == AndroidLevel2Backend.NPU) {
                    nativeLibraryDir
                } else {
                    null
                }
        )
    }

    private fun runBaseline(
        runtime: AndroidLevel2Runtime,
        modelFile: File,
        backend: AndroidLevel2Backend,
        workloads: List<AndroidCertificationWorkload>
    ): AndroidCertificationSample {
        initializeRuntime(runtime, modelFile, backend)

        return try {
            val started = SystemClock.elapsedRealtime()
            val results =
                workloads.map {
                    runOne(runtime, it)
                }

            buildSample(
                concurrency = 1,
                wallMs =
                    SystemClock.elapsedRealtime() - started,
                results = results
            )
        } finally {
            runCatching { runtime.close() }
        }
    }

    private suspend fun runConcurrencyTwo(
        modelFile: File,
        backend: AndroidLevel2Backend,
        workloads: List<AndroidCertificationWorkload>
    ): AndroidCertificationSample =
        coroutineScope {
            val runtimeA =
                AndroidLevel2Runtime(
                    File(
                        appContext.cacheDir,
                        "edgeswarm-cert-slot-a"
                    )
                )
            val runtimeB =
                AndroidLevel2Runtime(
                    File(
                        appContext.cacheDir,
                        "edgeswarm-cert-slot-b"
                    )
                )

            try {
                initializeRuntime(runtimeA, modelFile, backend)
                initializeRuntime(runtimeB, modelFile, backend)

                val laneA =
                    workloads.filterIndexed {
                        index, _ -> index % 2 == 0
                    }
                val laneB =
                    workloads.filterIndexed {
                        index, _ -> index % 2 == 1
                    }

                val started =
                    SystemClock.elapsedRealtime()

                val results =
                    listOf(
                        async(Dispatchers.IO) {
                            laneA.map {
                                runOne(runtimeA, it)
                            }
                        },
                        async(Dispatchers.IO) {
                            laneB.map {
                                runOne(runtimeB, it)
                            }
                        }
                    )
                        .awaitAll()
                        .flatten()
                        .sortedBy { it.workloadId }

                buildSample(
                    concurrency = 2,
                    wallMs =
                        SystemClock.elapsedRealtime() -
                            started,
                    results = results
                )
            } finally {
                runCatching { runtimeA.close() }
                runCatching { runtimeB.close() }
            }
        }

    private fun runOne(
        runtime: AndroidLevel2Runtime,
        workload: AndroidCertificationWorkload
    ): AndroidCertificationTaskResult {
        val started = SystemClock.elapsedRealtime()

        val inference =
            runtime.generate(workload.prompt)

        val wallMs =
            SystemClock.elapsedRealtime() - started

        return AndroidCertificationTaskResult(
            workloadId = workload.id,
            valid =
                validateOutput(
                    workload,
                    inference.text
                ),
            wallMs = wallMs,
            outputTokens = inference.outputTokens,
            decodeTokensPerSecond =
                inference.decodeTokensPerSecond
        )
    }

    private fun buildSample(
        concurrency: Int,
        wallMs: Long,
        results: List<AndroidCertificationTaskResult>
    ): AndroidCertificationSample {
        val sorted =
            results.map { it.wallMs }.sorted()

        val median =
            if (sorted.size % 2 == 1) {
                sorted[sorted.size / 2]
            } else {
                (
                    sorted[sorted.size / 2 - 1] +
                        sorted[sorted.size / 2]
                    ) / 2L
            }

        val totalOutputTokens =
            results.sumOf {
                it.outputTokens.coerceAtLeast(0)
            }

        val aggregateTps =
            if (wallMs > 0L && totalOutputTokens > 0) {
                totalOutputTokens.toDouble() /
                    (wallMs.toDouble() / 1000.0)
            } else {
                0.0
            }

        return AndroidCertificationSample(
            concurrency = concurrency,
            wallMs = wallMs,
            medianTaskMs = median,
            aggregateTokensPerSecond = aggregateTps,
            qualityPassRate =
                results.count { it.valid }.toDouble() /
                    results.size.toDouble(),
            results = results,
            thermalConstrained =
                isThermallyConstrained()
        )
    }

    private fun isThermallyConstrained(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }

        return powerManager.currentThermalStatus >=
            PowerManager.THERMAL_STATUS_MODERATE
    }

    private fun validateOutput(
        workload: AndroidCertificationWorkload,
        output: String
    ): Boolean {
        return runCatching {
            val trimmed =
                output
                    .trim()
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')

            check(start >= 0 && end > start)

            val json =
                JSONObject(
                    trimmed.substring(start, end + 1)
                )

            check(
                workload.requiredKeys.all {
                    json.has(it)
                }
            )

            check(
                workload.expectedValues.all {
                    (key, expected) ->
                    json.optString(key)
                        .equals(
                            expected,
                            ignoreCase = true
                        )
                }
            )

            check(
                workload.requiredTerms.all {
                    term ->
                    output.contains(
                        term,
                        ignoreCase = true
                    )
                }
            )

            true
        }.getOrDefault(false)
    }

    private fun persistCertificate(
        result: AndroidNeuralCapacityCertificationResult,
        concurrencyTwo: AndroidCertificationSample?,
        c2Accepted: Boolean
    ): File {
        val directory =
            File(
                appContext.filesDir,
                "capacity_certificates"
            )

        check(
            directory.exists() ||
                directory.mkdirs()
        ) {
            "Unable to create Android capacity certificate directory."
        }

        val file =
            File(
                directory,
                "android-neural-" +
                    result.modelSha256.take(16) +
                    "-" +
                    result.backend.telemetryName +
                    ".json"
            )

        val payload =
            JSONObject()
                .put(
                    "certificateVersion",
                    "edgeswarm-android-neural-capacity-v1"
                )
                .put(
                    "certificationPackId",
                    "edgeswarm-neural-realworld-v1"
                )
                .put("modelId", result.modelId)
                .put(
                    "modelSha256",
                    result.modelSha256
                )
                .put(
                    "modelCapability",
                    result.capability
                )
                .put(
                    "runtime",
                    "litert-lm"
                )
                .put(
                    "runtimeBackend",
                    result.backend.telemetryName
                )
                .put(
                    "certifiedConcurrency",
                    result.certifiedConcurrency
                )
                .put(
                    "testedConcurrencyLevels",
                    if (concurrencyTwo != null) {
                        "1,2"
                    } else {
                        "1"
                    }
                )
                .put(
                    "rejectedConcurrency",
                    if (
                        concurrencyTwo != null &&
                        !c2Accepted
                    ) {
                        2
                    } else {
                        JSONObject.NULL
                    }
                )
                .put(
                    "baselineMedianTaskMs",
                    result.baselineMedianTaskMs
                )
                .put(
                    "baselineAggregateTokensPerSecond",
                    result.baselineAggregateTokensPerSecond
                )
                .put(
                    "certifiedMedianTaskMs",
                    result.certifiedMedianTaskMs
                )
                .put(
                    "certifiedAggregateTokensPerSecond",
                    result.certifiedAggregateTokensPerSecond
                )
                .put(
                    "qualityPassRate",
                    result.qualityPassRate
                )
                .put(
                    "createdAtUnixMs",
                    System.currentTimeMillis()
                )

        file.writeText(payload.toString(2))
        return file
    }

    private fun certificationWorkloads():
        List<AndroidCertificationWorkload> =
        listOf(
            AndroidCertificationWorkload(
                id = "sentiment-mixed-01",
                prompt =
                    "Return only valid JSON with keys sentiment, confidence, reason. " +
                        "Classify this feedback: I really like the new dashboard and it " +
                        "feels much faster than before, but report exports keep failing " +
                        "and that problem is blocking my weekly workflow.",
                requiredKeys =
                    setOf(
                        "sentiment",
                        "confidence",
                        "reason"
                    ),
                expectedValues =
                    mapOf("sentiment" to "mixed")
            ),
            AndroidCertificationWorkload(
                id = "sentiment-negative-02",
                prompt =
                    "Return only valid JSON with keys sentiment, confidence, reason. " +
                        "Classify this feedback: The application has been frustrating " +
                        "to use, the integration repeatedly fails, and I have not found " +
                        "anything about the experience that I would describe positively.",
                requiredKeys =
                    setOf(
                        "sentiment",
                        "confidence",
                        "reason"
                    ),
                expectedValues =
                    mapOf("sentiment" to "negative")
            ),
            AndroidCertificationWorkload(
                id = "support-triage-billing-01",
                prompt =
                    "Return only valid JSON with keys category, priority, summary, " +
                        "next_action. Use category exactly one of billing, technical, " +
                        "account, or other. Use priority exactly one of low, medium, or " +
                        "high. A customer says they were charged twice for the " +
                        "same subscription renewal. Their accounting close is tomorrow " +
                        "and they need the duplicate charge investigated before finance " +
                        "finishes the close.",
                requiredKeys =
                    setOf(
                        "category",
                        "priority",
                        "summary",
                        "next_action"
                    ),
                expectedValues =
                    mapOf(
                        "category" to "billing",
                        "priority" to "high"
                    )
            ),
            AndroidCertificationWorkload(
                id = "support-triage-technical-02",
                prompt =
                    "Return only valid JSON with keys category, priority, summary, " +
                        "next_action. Use category exactly one of billing, technical, " +
                        "account, or other. Use priority exactly one of low, medium, or " +
                        "high. A customer's CRM integration stopped syncing new " +
                        "records this morning and their sales operations workflow is " +
                        "blocked. Other parts of the account are working normally.",
                requiredKeys =
                    setOf(
                        "category",
                        "priority",
                        "summary",
                        "next_action"
                    ),
                expectedValues =
                    mapOf(
                        "category" to "technical",
                        "priority" to "high"
                    )
            ),
            AndroidCertificationWorkload(
                id = "email-rewrite-schedule-01",
                prompt =
                    "Return only valid JSON with keys subject and body. Rewrite this " +
                        "professionally and concisely while preserving Friday and budget: " +
                        "Hi Maya, can we move our meeting to Friday? I need another day " +
                        "to finish the budget review and would rather send completed " +
                        "numbers before we meet. Thanks.",
                requiredKeys =
                    setOf("subject", "body"),
                requiredTerms =
                    setOf("Friday", "budget")
            ),
            AndroidCertificationWorkload(
                id = "email-rewrite-client-02",
                prompt =
                    "Return only valid JSON with keys subject and body. Rewrite this " +
                        "professionally and concisely while preserving onboarding and " +
                        "tomorrow: Hi Daniel, we finished the revised onboarding document " +
                        "and I would like you to review it before we send it to the client. " +
                        "Please send final comments by tomorrow afternoon.",
                requiredKeys =
                    setOf("subject", "body"),
                requiredTerms =
                    setOf(
                        "onboarding",
                        "tomorrow"
                    )
            )
        )
}
