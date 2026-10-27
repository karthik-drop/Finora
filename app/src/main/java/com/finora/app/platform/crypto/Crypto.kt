package com.finora.app.platform.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Vault files are encrypted with an AES-256-GCM key that lives in the Android Keystore and
 * never leaves it. Consequence (stated on the privacy screen): uninstalling the app or clearing
 * its data destroys the key, and the vault files cannot be read again except from a backup.
 */
object VaultCipher {
    private const val ALIAS = "finora_vault_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LEN = 12

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(input: InputStream, output: OutputStream) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        output.write(cipher.iv)
        CipherOutputStream(output, cipher).use { input.copyTo(it) }
    }

    fun decryptingStream(input: InputStream): InputStream {
        val iv = ByteArray(IV_LEN)
        var read = 0
        while (read < IV_LEN) {
            val n = input.read(iv, read, IV_LEN - read)
            require(n > 0) { "Vault file is truncated" }
            read += n
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return CipherInputStream(input, cipher)
    }
}

/**
 * Backups are encrypted with a key derived from a password the user chooses
 * (PBKDF2-HMAC-SHA256, 210,000 iterations, random salt) and AES-256-GCM. The password is not
 * stored anywhere: lose it and the backup cannot be opened, by anyone.
 */
object PasswordCipher {
    private val MAGIC = "FINORA-BK1".toByteArray()
    private const val ITERATIONS = 210_000
    private const val SALT_LEN = 16
    private const val IV_LEN = 12

    private fun derive(password: CharArray, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    fun encryptingStream(password: CharArray, output: OutputStream): OutputStream {
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_LEN).also(rnd::nextBytes)
        val iv = ByteArray(IV_LEN).also(rnd::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv)) }
        output.write(MAGIC); output.write(salt); output.write(iv)
        return CipherOutputStream(output, cipher)
    }

    /** Throws [BadBackup] for a file that is not a Finora backup; a wrong password fails on read. */
    fun decryptingStream(password: CharArray, input: InputStream): InputStream {
        val header = ByteArray(MAGIC.size + SALT_LEN + IV_LEN)
        var read = 0
        while (read < header.size) {
            val n = input.read(header, read, header.size - read)
            if (n <= 0) throw BadBackup("This file is not a Finora backup.")
            read += n
        }
        if (!header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw BadBackup("This file is not a Finora backup.")
        val salt = header.copyOfRange(MAGIC.size, MAGIC.size + SALT_LEN)
        val iv = header.copyOfRange(MAGIC.size + SALT_LEN, header.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv)) }
        return CipherInputStream(input, cipher)
    }

    class BadBackup(message: String) : Exception(message)
}
