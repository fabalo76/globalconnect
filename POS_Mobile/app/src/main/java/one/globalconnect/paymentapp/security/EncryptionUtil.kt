package one.globalconnect.paymentapp.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import java.security.KeyStore
import java.security.SecureRandom
import java.security.UnrecoverableEntryException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec


/**
 * EncryptionUtil provides secure encryption and key management functionalities.
 * It is used to generate and store the database passphrase securely in Android Keystore
 * and to encrypt and decrypt sensitive data.
 */
object EncryptionUtil {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore" // Keystore alias for secure key storage
    private const val KEY_ALIAS = "DBEncryptionKey" // Alias for database encryption key
    private const val PREF_NAME = "SecurePrefs" // SharedPreferences name for storing passphrase
    private const val DB_PASS_KEY = "DBPassphrase" // Key for storing the database passphrase
    private const val TAG = "EncryptionUtil"

    /**
     * Generates or retrieves the database encryption passphrase.
     *
     * - If a passphrase already exists in SharedPreferences, it is retrieved and returned.
     * - If no passphrase exists, a 256-bit passphrase is derived from the device serial number (with fallbacks), stored securely, and returned.
     *
     * @param context The application context for accessing SharedPreferences.
     * @return A 32-byte encryption passphrase for the SQLCipher database.
     */
    fun getDatabasePassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val storedPassphrase = prefs.getString(DB_PASS_KEY, null)

        if (!storedPassphrase.isNullOrEmpty()) {
            decodeStoredPassphrase(storedPassphrase)?.let { decoded ->
                Log.i(TAG, "Retrieved stored database passphrase")
                return decoded
            }
        }

        loadLegacyPassphrase(context)?.let { legacy ->
            Log.i(TAG, "Loaded legacy database passphrase")
            persistPassphrase(prefs, legacy)
            clearLegacyPassphrase(context)
            return legacy
        }

        val derivedPass = deriveSerialBasedPassphrase(context)
        Log.i(TAG, "Generated new database passphrase")
        persistPassphrase(prefs, derivedPass)
        return derivedPass
    }

    /**
     * Retrieves or generates an AES encryption key stored in the Android Keystore.
     *
     * - If a key exists in the Keystore, it is retrieved.
     * - If no key exists, a new AES-256 GCM key is generated and stored in the Keystore.
     *
     * @return A securely stored AES encryption key.
     */
    private fun getEncryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            try {
                val secretKeyEntry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
                return secretKeyEntry.secretKey
            } catch (error: Exception) {
                if (error is UnrecoverableEntryException || error is ClassCastException) {
                    Log.w(TAG, "Stored encryption key is invalid. Generating a new key.", error)
                    runCatching { keyStore.deleteEntry(KEY_ALIAS) }
                } else {
                    throw error
                }
            }
        }
        return generateNewEncryptionKey()
    }

    private fun generateNewEncryptionKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false) // No authentication required for decryption
                .build()
        )
        return keyGenerator.generateKey()
    }

    /**
     * Encrypts a given plaintext string using AES-GCM encryption.
     *
     * - A unique IV (Initialization Vector) is generated for each encryption.
     * - The encrypted data is Base64 encoded along with the IV for secure storage.
     *
     * @param plaintext The data to be encrypted.
     * @return A Base64 encoded string containing the IV and encrypted data.
     */
    fun encryptData(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getEncryptionKey())
        val iv = cipher.iv // Generate a new IV for this encryption
        val encryptedData = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Combine IV and encrypted data, then encode to Base64
        return Base64.encodeToString(iv + encryptedData, Base64.DEFAULT)
    }

    /**
     * Decrypts an AES-GCM encrypted string.
     *
     * - Extracts the IV and encrypted data from the Base64 encoded string.
     * - Uses the stored AES key to decrypt the data.
     *
     * @param encryptedData The Base64 encoded string containing the IV and encrypted content.
     * @return The original decrypted plaintext string.
     */
    fun decryptData(encryptedData: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val encryptedBytes = Base64.decode(encryptedData, Base64.DEFAULT)

        // Extract IV and encrypted content
        val iv = encryptedBytes.copyOfRange(0, 12)
        val encryptedContent = encryptedBytes.copyOfRange(12, encryptedBytes.size)

        // Initialize cipher with IV and decrypt the data
        cipher.init(Cipher.DECRYPT_MODE, getEncryptionKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encryptedContent), Charsets.UTF_8)
    }

    /**
     * Hashes a PAN (Primary Account Number) using SHA-256.
     *
     * - This function is used to securely store and compare PANs without exposing the actual number.
     * - Hashing is **one-way**, meaning the original PAN **cannot be retrieved** from the hash.
     *
     * @param pan The PAN (card number) to be hashed.
     * @return A Base64 encoded SHA-256 hash of the PAN.
     */
    fun hashPAN(pan: String): String {
        val messageDigest = java.security.MessageDigest.getInstance("SHA-256")
        val hashedBytes = messageDigest.digest(pan.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hashedBytes, Base64.DEFAULT)
    }

    private fun decodeStoredPassphrase(encryptedValue: String): ByteArray? {
        return runCatching {
            val encodedPass = decryptData(encryptedValue)
            Base64.decode(encodedPass, Base64.DEFAULT)
        }.getOrNull()
    }

    private fun persistPassphrase(prefs: SharedPreferences, passphrase: ByteArray) {
        val encodedPass = Base64.encodeToString(passphrase, Base64.NO_WRAP)
        val encryptedValue = encryptData(encodedPass)
        prefs.edit().putString(DB_PASS_KEY, encryptedValue).apply()
    }

    private fun deriveSerialBasedPassphrase(context: Context): ByteArray {
        val identifier = getDeviceIdentifier(context)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(identifier.toByteArray(Charsets.UTF_8))
        digest.update(context.packageName.toByteArray(Charsets.UTF_8))
        return digest.digest()
    }

    private fun getDeviceIdentifier(context: Context): String {
        val hardwareIdentifiers = mutableListOf<String>()

        val nexgoSerial = runCatching { GlobalConnectPaymentApplication.serialNumber }
            .onFailure { error ->
                Log.w(TAG, "Unable to retrieve Nexgo device serial number", error)
            }
            .getOrNull()
            ?.takeUnless { it.isBlank() || it == Build.UNKNOWN }

        if (!nexgoSerial.isNullOrBlank()) {
            hardwareIdentifiers += nexgoSerial
        }

        val nexgoModel = runCatching { GlobalConnectPaymentApplication.model }
            .onFailure { error ->
                Log.w(TAG, "Unable to retrieve Nexgo device model", error)
            }
            .getOrNull()
            ?.takeUnless { it.isBlank() }

        if (!nexgoModel.isNullOrBlank()) {
            hardwareIdentifiers += nexgoModel
        }

        if (hardwareIdentifiers.isNotEmpty()) {
            return hardwareIdentifiers.joinToString(":")
        }

        val legacySerial = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Build.getSerial()
            } else {
                @Suppress("DEPRECATION")
                Build.SERIAL
            }
        }.onFailure { error ->
            Log.w(TAG, "Unable to read device serial number", error)
        }.getOrNull()?.takeUnless { value ->
            value.isNullOrBlank() || value == Build.UNKNOWN
        }

        if (legacySerial != null) {
            return legacySerial
        }

        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeUnless { it.isNullOrBlank() }

        if (androidId != null) {
            Log.w(TAG, "Using ANDROID_ID as fallback identifier for database key derivation")
            return androidId
        }

        Log.w(TAG, "Falling back to random database key material; no stable device identifier available")
        val fallback = ByteArray(32).apply { SecureRandom().nextBytes(this) }
        return Base64.encodeToString(fallback, Base64.NO_WRAP)
    }

    private fun loadLegacyPassphrase(context: Context): ByteArray? {
        return runCatching {
            createLegacyEncryptedPreferences(context).getString(DB_PASS_KEY, null)
        }.getOrNull()?.let { legacyEncoded ->
            Base64.decode(legacyEncoded, Base64.DEFAULT)
        }
    }

    private fun clearLegacyPassphrase(context: Context) {
        runCatching {
            createLegacyEncryptedPreferences(context).edit().remove(DB_PASS_KEY).apply()
        }
    }

    @Suppress("DEPRECATION")
    private fun createLegacyEncryptedPreferences(context: Context): SharedPreferences {
        val masterKey = androidx.security.crypto.MasterKey.Builder(context)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
            .build()

        return androidx.security.crypto.EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
