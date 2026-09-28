package com.edgeswarm.node

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

// SWARM_ANDROID_VERIFIED_AUTO_UPDATE_V1
//
// Update contract:
// 1. HTTPS only.
// 2. Backend SHA-256 must match exactly.
// 3. APK package must match this application.
// 4. APK versionName must match the release manifest.
// 5. APK versionCode must be newer than the installed app.
// 6. APK must share the installed signing identity.
// 7. Only then may Android's package installer be opened.
object AndroidAutoUpdater {

    data class PreparedUpdate(
        val apkFile: File,
        val versionName: String,
        val versionCode: Long,
        val sha256: String
    )

    private const val APK_MIME =
        "application/vnd.android.package-archive"

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(
                20,
                TimeUnit.SECONDS
            )
            .readTimeout(
                120,
                TimeUnit.SECONDS
            )
            .writeTimeout(
                30,
                TimeUnit.SECONDS
            )
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    suspend fun downloadAndVerify(
        context: Context,
        expectedVersion: String,
        downloadUrl: String,
        expectedSha256: String,
        expectedPackageName: String
    ): PreparedUpdate =
        withContext(Dispatchers.IO) {

            val normalizedVersion =
                normalizeVersion(expectedVersion)

            require(normalizedVersion.isNotBlank()) {
                "Update manifest version is missing."
            }

            val uri =
                Uri.parse(
                    downloadUrl
                )

            require(
                uri.scheme.equals(
                    "https",
                    ignoreCase = true
                )
            ) {
                "Update URL must use HTTPS."
            }

            val packageName =
                expectedPackageName
                    .trim()
                    .ifBlank {
                        BuildConfig.APPLICATION_ID
                    }

            require(
                packageName ==
                    BuildConfig.APPLICATION_ID
            ) {
                "Update package does not match this application."
            }

            val expectedHash =
                expectedSha256
                    .trim()
                    .uppercase()

            require(
                Regex(
                    "^[A-F0-9]{64}$"
                ).matches(
                    expectedHash
                )
            ) {
                "Update manifest SHA-256 is invalid."
            }

            val updateDir =
                File(
                    context.cacheDir,
                    "updates"
                ).apply {
                    mkdirs()
                }

            val safeVersion =
                normalizedVersion
                    .replace(
                        Regex(
                            "[^A-Za-z0-9._-]"
                        ),
                        "_"
                    )

            val tempFile =
                File(
                    updateDir,
                    "Swarm_$safeVersion.apk.part"
                )

            val finalFile =
                File(
                    updateDir,
                    "Swarm_$safeVersion.apk"
                )

            tempFile.delete()

            val request =
                Request.Builder()
                    .url(
                        downloadUrl
                    )
                    .get()
                    .build()

            try {
                client
                    .newCall(
                        request
                    )
                    .execute()
                    .use { response ->

                        if (
                            !response.isSuccessful
                        ) {
                            throw IllegalStateException(
                                "Update download failed with HTTP ${response.code}."
                            )
                        }

                        val responseBody =
                            response.body
                                ?: throw IllegalStateException(
                                    "Update download returned no APK body."
                                )

                        responseBody
                            .byteStream()
                            .use { input ->

                                tempFile
                                    .outputStream()
                                    .buffered()
                                    .use { output ->
                                        input.copyTo(
                                            output
                                        )
                                    }
                            }
                    }

                val actualHash =
                    sha256(
                        tempFile
                    )

                if (
                    actualHash !=
                    expectedHash
                ) {
                    throw IllegalStateException(
                        "Downloaded APK SHA-256 does not match the trusted release manifest."
                    )
                }

                val packageInfo =
                    readArchivePackageInfo(
                        context,
                        tempFile
                    )
                        ?: throw IllegalStateException(
                            "Downloaded file is not a readable Android APK."
                        )

                require(
                    packageInfo.packageName ==
                        packageName
                ) {
                    "Downloaded APK package identity does not match Swarm."
                }

                val archiveVersion =
                    normalizeVersion(
                        packageInfo.versionName
                    )

                require(
                    archiveVersion ==
                        normalizedVersion
                ) {
                    "Downloaded APK version does not match the release manifest."
                }

                val currentInfo =
                    readInstalledPackageInfo(
                        context
                    )

                val currentCode =
                    versionCodeOf(
                        currentInfo
                    )

                val archiveCode =
                    versionCodeOf(
                        packageInfo
                    )

                require(
                    archiveCode >
                        currentCode
                ) {
                    "Downloaded APK versionCode is not newer than the installed app."
                }

                val installedSigners =
                    signerDigests(
                        currentInfo
                    )

                val archiveSigners =
                    signerDigests(
                        packageInfo
                    )

                require(
                    installedSigners.isNotEmpty() &&
                    archiveSigners.isNotEmpty() &&
                    installedSigners
                        .intersect(
                            archiveSigners
                        )
                        .isNotEmpty()
                ) {
                    "Downloaded APK signing identity does not match the installed Swarm app."
                }

                if (
                    finalFile.exists()
                ) {
                    finalFile.delete()
                }

                if (
                    !tempFile.renameTo(
                        finalFile
                    )
                ) {
                    tempFile.copyTo(
                        finalFile,
                        overwrite = true
                    )

                    tempFile.delete()
                }

                PreparedUpdate(
                    apkFile =
                        finalFile,
                    versionName =
                        archiveVersion,
                    versionCode =
                        archiveCode,
                    sha256 =
                        actualHash
                )
            } catch (
                error: Throwable
            ) {
                tempFile.delete()
                throw error
            }
        }

    fun canRequestPackageInstalls(
        context: Context
    ): Boolean {
        return (
            Build.VERSION.SDK_INT <
                Build.VERSION_CODES.O ||
            context
                .packageManager
                .canRequestPackageInstalls()
        )
    }

    fun unknownSourcesSettingsIntent(
        context: Context
    ): Intent {
        return Intent(
            Settings
                .ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse(
                "package:${context.packageName}"
            )
        )
    }

    fun launchInstaller(
        context: Context,
        apkFile: File
    ) {
        require(
            apkFile.isFile
        ) {
            "Verified update APK is missing."
        }

        val apkUri =
            FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.update-files",
                apkFile
            )

        val intent =
            Intent(
                Intent.ACTION_VIEW
            ).apply {
                setDataAndType(
                    apkUri,
                    APK_MIME
                )

                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }

        context.startActivity(
            intent
        )
    }

    private fun normalizeVersion(
        value: String?
    ): String {
        return value
            ?.trim()
            ?.removePrefix(
                "v"
            )
            ?.removePrefix(
                "V"
            )
            .orEmpty()
    }

    private fun sha256(
        file: File
    ): String {
        val digest =
            MessageDigest.getInstance(
                "SHA-256"
            )

        file
            .inputStream()
            .buffered()
            .use { input ->

                val buffer =
                    ByteArray(
                        64 * 1024
                    )

                while (
                    true
                ) {
                    val read =
                        input.read(
                            buffer
                        )

                    if (
                        read <= 0
                    ) {
                        break
                    }

                    digest.update(
                        buffer,
                        0,
                        read
                    )
                }
            }

        return digest
            .digest()
            .joinToString(
                ""
            ) {
                "%02X".format(
                    it
                )
            }
    }

    private fun sha256(
        bytes: ByteArray
    ): String {
        return MessageDigest
            .getInstance(
                "SHA-256"
            )
            .digest(
                bytes
            )
            .joinToString(
                ""
            ) {
                "%02X".format(
                    it
                )
            }
    }

    @Suppress(
        "DEPRECATION"
    )
    private fun packageInfoFlags(): Int {
        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.P
        ) {
            PackageManager
                .GET_SIGNING_CERTIFICATES
        } else {
            PackageManager
                .GET_SIGNATURES
        }
    }

    @Suppress(
        "DEPRECATION"
    )
    private fun readInstalledPackageInfo(
        context: Context
    ): PackageInfo {
        val packageManager =
            context.packageManager

        val flags =
            packageInfoFlags()

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            packageManager
                .getPackageInfo(
                    context.packageName,
                    PackageManager
                        .PackageInfoFlags
                        .of(
                            flags.toLong()
                        )
                )
        } else {
            packageManager
                .getPackageInfo(
                    context.packageName,
                    flags
                )
        }
    }

    @Suppress(
        "DEPRECATION"
    )
    private fun readArchivePackageInfo(
        context: Context,
        apkFile: File
    ): PackageInfo? {
        val packageManager =
            context.packageManager

        val flags =
            packageInfoFlags()

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            packageManager
                .getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager
                        .PackageInfoFlags
                        .of(
                            flags.toLong()
                        )
                )
        } else {
            packageManager
                .getPackageArchiveInfo(
                    apkFile.absolutePath,
                    flags
                )
        }
    }

    @Suppress(
        "DEPRECATION"
    )
    private fun versionCodeOf(
        info: PackageInfo
    ): Long {
        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.P
        ) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }

    @Suppress(
        "DEPRECATION"
    )
    private fun signerDigests(
        info: PackageInfo
    ): Set<String> {
        val signatures =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.P
            ) {
                val signingInfo =
                    info.signingInfo
                        ?: return emptySet()

                if (
                    signingInfo
                        .hasMultipleSigners()
                ) {
                    signingInfo
                        .apkContentsSigners
                        .toList()
                } else {
                    signingInfo
                        .signingCertificateHistory
                        .toList()
                }
            } else {
                info.signatures
                    ?.toList()
                    .orEmpty()
            }

        return signatures
            .map {
                sha256(
                    it.toByteArray()
                )
            }
            .toSet()
    }
}