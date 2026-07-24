package com.edgeswarm.node

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the node wallet private key encrypted by Android Keystore.
 *
 * The key remains available to the foreground node without a biometric prompt because
 * task results and attestations must be signed while the UI is not visible.
 *
 * Payload format:
 * - ks1: legacy AES-GCM payload without additional authenticated data.
 * - ks2: AES-GCM payload bound to the normalized provider email as AAD.
 */
object WalletVault {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val PREFS_NAME = "EdgeSwarmNode"
    private const val LEGACY_PAYLOAD_PREFIX = "ks1:"
    private const val CURRENT_PAYLOAD_PREFIX = "ks2:"
    private const val GCM_IV_BYTES = 12

    fun storePrivateKey(context: Context, email: String, privateKey: String) {
        val normalizedEmail = normalizeEmail(email)
        val normalizedPrivateKey = normalizePrivateKey(privateKey)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey(normalizedEmail))
        cipher.updateAAD(aad(normalizedEmail))

        val encrypted = cipher.doFinal(normalizedPrivateKey.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        val encoded = CURRENT_PAYLOAD_PREFIX +
            Base64.encodeToString(payload, Base64.NO_WRAP)

        val committed = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(preferenceKey(normalizedEmail), encoded)
            .remove("private_key_$email")
            .remove("encrypted_wallet_$email")
            .remove("private_key_$normalizedEmail")
            .remove("encrypted_wallet_$normalizedEmail")
            .commit()

        check(committed) { "Could not persist the protected node wallet." }
    }

    fun loadPrivateKey(context: Context, email: String): String? {
        if (email.isBlank()) return null

        val normalizedEmail = normalizeEmail(email)
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val stored = prefs.getString(preferenceKey(normalizedEmail), null)
            ?: return null

        val legacyPayload = stored.startsWith(LEGACY_PAYLOAD_PREFIX)
        val currentPayload = stored.startsWith(CURRENT_PAYLOAD_PREFIX)

        require(legacyPayload || currentPayload) {
            "Unsupported wallet vault payload format."
        }

        val prefix = if (currentPayload) CURRENT_PAYLOAD_PREFIX else LEGACY_PAYLOAD_PREFIX
        val payload = Base64.decode(stored.removePrefix(prefix), Base64.NO_WRAP)

        require(payload.size > GCM_IV_BYTES) {
            "Wallet vault payload is invalid."
        }

        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val encrypted = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        val secretKey = getExistingSecretKey(normalizedEmail)
            ?: throw IllegalStateException(
                "Android Keystore key is unavailable for this wallet."
            )

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey,
            GCMParameterSpec(128, iv)
        )

        if (currentPayload) {
            cipher.updateAAD(aad(normalizedEmail))
        }

        val privateKey = normalizePrivateKey(
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        )

        if (legacyPayload) {
            // Transparently migrate the old payload to the email-bound ks2 format.
            storePrivateKey(context, normalizedEmail, privateKey)
        }

        return privateKey
    }

    fun hasPrivateKey(context: Context, email: String): Boolean =
        runCatching {
            !loadPrivateKey(context, email).isNullOrBlank()
        }.getOrDefault(false)

    private fun getOrCreateSecretKey(normalizedEmail: String): SecretKey {
        getExistingSecretKey(normalizedEmail)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val spec = KeyGenParameterSpec.Builder(
            alias(normalizedEmail),
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()

        generator.init(spec)
        return generator.generateKey()
    }

    private fun getExistingSecretKey(normalizedEmail: String): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }

        return keyStore.getKey(alias(normalizedEmail), null) as? SecretKey
    }

    private fun normalizeEmail(email: String): String {
        val normalized = email.trim().lowercase()
        require(normalized.isNotBlank()) { "Provider email is required." }
        return normalized
    }

    private fun normalizePrivateKey(privateKey: String): String {
        val normalized = privateKey
            .trim()
            .removePrefix("0x")
            .removePrefix("0X")

        require(normalized.matches(Regex("^[0-9a-fA-F]{1,64}$"))) {
            "Wallet private key format is invalid."
        }

        return normalized.lowercase().padStart(64, '0')
    }

    private fun aad(normalizedEmail: String): ByteArray =
        "edgeswarm-wallet-v2:$normalizedEmail".toByteArray(Charsets.UTF_8)

    private fun alias(normalizedEmail: String): String =
        "edgeswarm_wallet_${sha256(normalizedEmail)}"

    private fun preferenceKey(normalizedEmail: String): String =
        "keystore_wallet_${sha256(normalizedEmail)}"

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
