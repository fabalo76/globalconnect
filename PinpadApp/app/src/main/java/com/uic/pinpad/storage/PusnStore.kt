package com.uic.pinpad.storage

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PusnStore(
    context: Context,
    private val deviceSerialProvider: () -> String = { "" },
) {
    private val appContext = context.applicationContext
    private val prefs = PinpadPreferences(appContext)
    private val secureRandom = SecureRandom()

    fun read(slot: Int): String {
        val preferenceValue = prefs.pusn(slot)
        if (preferenceValue.isNotBlank()) return preferenceValue

        val persistentValue = readPersistentProperties()
            ?.getProperty(slotKey(slot))
            .orEmpty()
        if (persistentValue.isNotBlank()) {
            prefs.setPusn(slot, persistentValue)
        }
        return persistentValue
    }

    fun write(slot: Int, pusn: String): Boolean {
        prefs.setPusn(slot, pusn)
        val preferenceSaved = prefs.pusn(slot) == pusn
        val properties = readPersistentProperties() ?: Properties()
        properties.setProperty(slotKey(slot), pusn)
        val persistentSaved = writeEncryptedProperties(properties)
        if (!persistentSaved) {
            Log.w(TAG, "Unable to update encrypted PUSN backup")
        }
        return preferenceSaved
    }

    private fun readPersistentProperties(): Properties? {
        readEncryptedProperties()?.let { return it }
        val migrated = readLegacyPlaintextProperties() ?: return null
        if (writeEncryptedProperties(migrated)) {
            removeLegacyPlaintextFiles()
        }
        return migrated
    }

    private fun readEncryptedProperties(): Properties? {
        val file = encryptedFile()
        return runCatching {
            if (!file.isFile) return null
            val lines = file.readLines(StandardCharsets.US_ASCII)
            if (lines.size != ENCRYPTED_LINE_COUNT || lines.first() != ENCRYPTED_HEADER) return null
            val iv = Base64.getDecoder().decode(lines[1])
            val cipherText = Base64.getDecoder().decode(lines[2])
            bytesToProperties(decrypt(cipherText, iv))
        }.onFailure {
            Log.w(TAG, "Unable to read encrypted PUSN backup", it)
        }.getOrNull()
    }

    private fun writeEncryptedProperties(properties: Properties): Boolean {
        return runCatching {
            val file = encryptedFile()
            file.parentFile?.mkdirs()
            val iv = ByteArray(GCM_IV_BYTES).also(secureRandom::nextBytes)
            val cipherText = encrypt(propertiesToBytes(properties), iv)
            file.writeText(
                listOf(
                    ENCRYPTED_HEADER,
                    Base64.getEncoder().encodeToString(iv),
                    Base64.getEncoder().encodeToString(cipherText),
                ).joinToString(separator = "\n"),
                StandardCharsets.US_ASCII,
            )
            true
        }.onFailure {
            Log.w(TAG, "Unable to write encrypted PUSN backup", it)
        }.getOrDefault(false)
    }

    private fun encrypt(plainText: ByteArray, iv: ByteArray): ByteArray {
        return Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, encryptionKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }.doFinal(plainText)
    }

    private fun decrypt(cipherText: ByteArray, iv: ByteArray): ByteArray {
        return Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }.doFinal(cipherText)
    }

    private fun encryptionKey(): SecretKeySpec {
        val keyMaterial = "$KEY_PURPOSE|${deviceSerialProvider().trim()}|$APP_SECRET"
        val keyBytes = MessageDigest.getInstance("SHA-256")
            .digest(keyMaterial.toByteArray(StandardCharsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun propertiesToBytes(properties: Properties): ByteArray {
        return ByteArrayOutputStream().use { output ->
            properties.store(output, "PINPAD permanent unit serial numbers")
            output.toByteArray()
        }
    }

    private fun bytesToProperties(bytes: ByteArray): Properties {
        return Properties().apply {
            ByteArrayInputStream(bytes).use(::load)
        }
    }

    private fun readLegacyPlaintextProperties(): Properties? {
        for (file in legacyPlaintextFiles()) {
            val properties = runCatching {
                if (!file.isFile) return@runCatching null
                Properties().apply { file.inputStream().use(::load) }
            }.onFailure {
                Log.w(TAG, "Unable to read legacy plaintext PUSN backup", it)
            }.getOrNull()
            if (properties != null) return properties
        }
        return null
    }

    private fun removeLegacyPlaintextFiles() {
        legacyPlaintextFiles().forEach { file ->
            runCatching {
                if (file.isFile) file.delete()
            }.onFailure {
                Log.w(TAG, "Unable to remove legacy plaintext PUSN backup", it)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun encryptedFile(): File {
        val baseDir = appContext.externalMediaDirs
            .firstOrNull()
            ?: File(appContext.filesDir, PRIVATE_DIRECTORY_NAME)
        return File(baseDir, "$PRIVATE_DIRECTORY_NAME/$ENCRYPTED_FILE_NAME")
    }

    @Suppress("DEPRECATION")
    private fun legacyPlaintextFiles(): List<File> {
        val files = mutableListOf<File>()
        runCatching {
            files += File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "$LEGACY_DIRECTORY_NAME/$LEGACY_FILE_NAME",
            )
        }
        runCatching {
            files += File(Environment.getExternalStorageDirectory(), "$LEGACY_DIRECTORY_NAME/$LEGACY_FILE_NAME")
        }
        return files.distinctBy { it.absolutePath }
    }

    private fun slotKey(slot: Int): String = "slot_$slot"

    companion object {
        private const val TAG = "PusnStore"
        private const val KEY_PURPOSE = "PINPAD-PUSN-BACKUP"
        private const val APP_SECRET = "6b3cY5vN2xW9qR8mL4sT7pZ1aF0dH6jK"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val ENCRYPTED_HEADER = "PINPAD-PUSN-v1"
        private const val ENCRYPTED_LINE_COUNT = 3
        private const val PRIVATE_DIRECTORY_NAME = ".pinpad"
        private const val ENCRYPTED_FILE_NAME = "pusn.dat"
        private const val LEGACY_DIRECTORY_NAME = "PINPAD"
        private const val LEGACY_FILE_NAME = "pusn.properties"
    }
}
