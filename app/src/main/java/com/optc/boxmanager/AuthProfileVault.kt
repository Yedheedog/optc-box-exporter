package com.optc.boxmanager

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the imported Farm Lite device profile as authenticated ciphertext in app-private storage.
 * The source is read only into memory; plaintext is never written to disk or logged.
 */
object AuthProfileVault {
    private const val KEY_ALIAS = "optc_box_manager_auth_source_v1"
    private const val FILE_NAME = "farm_lite_auth_source.enc"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12

    fun hasProfile(context: Context): Boolean = encryptedFile(context).let { it.exists() && it.length() > 0 }

    fun import(context: Context, uri: Uri) {
        val plaintext = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("The selected file could not be opened.")
        require(plaintext.isNotEmpty()) { "The selected file is empty." }
        val encrypted = encrypt(plaintext)
        plaintext.fill(0)
        val target = encryptedFile(context)
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeBytes(encrypted)
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        encrypted.fill(0)
    }

    private fun encryptedFile(context: Context) =
        File(File(context.filesDir, "auth_source").apply { mkdirs() }, FILE_NAME)

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(1 + iv.size + ciphertext.size)
            .put(1)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
