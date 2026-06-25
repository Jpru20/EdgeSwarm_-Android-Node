package com.edgeswarm.node

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object WalletVault {
    private fun deriveKey(password: String, email: String): ByteArray {
        // Use the email as a deterministic 16-byte salt (Matches Python)
        val salt = email.toByteArray(StandardCharsets.UTF_8).copyOf(16)
        val spec = PBEKeySpec(password.toCharArray(), salt, 100000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    fun encrypt(privateKey: String, password: String, email: String): String {
        val keyBytes = deriveKey(password, email)
        val secretKey = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = ByteArray(12)
        SecureRandom().nextBytes(nonce)
        val parameterSpec = GCMParameterSpec(128, nonce)

        cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec)
        val cipherText = cipher.doFinal(privateKey.toByteArray(StandardCharsets.UTF_8))

        // Combine nonce and ciphertext and base64 encode
        val combined = nonce + cipherText
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(encryptedPayload: String, password: String, email: String): String {
        val keyBytes = deriveKey(password, email)
        val secretKey = SecretKeySpec(keyBytes, "AES")

        val data = Base64.decode(encryptedPayload, Base64.NO_WRAP)
        val nonce = data.copyOfRange(0, 12)
        val cipherText = data.copyOfRange(12, data.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val parameterSpec = GCMParameterSpec(128, nonce)

        cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec)
        val plainText = cipher.doFinal(cipherText)

        return String(plainText, StandardCharsets.UTF_8)
    }
}