package com.astracare.core.data.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.astracare.core.domain.repository.LocalStoreUnavailableException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The SQLCipher passphrase for the local database.
 *
 * ## The shape of the problem
 *
 * SQLCipher needs a passphrase — bytes it can read. The Android Keystore's whole value is that
 * its keys **cannot** be read: they live in the TEE or a secure element and only ever act on
 * data handed to them. The two requirements are directly opposed, so the passphrase cannot
 * simply *be* a Keystore key.
 *
 * The standard resolution, and the one here, is one level of indirection:
 *
 *  1. Generate 32 random bytes once, from [SecureRandom]. That is the database passphrase.
 *  2. Encrypt those bytes with an AES-GCM key that lives in the Keystore and never leaves it.
 *  3. Store the *wrapped* result in ordinary `SharedPreferences`.
 *
 * The file on disk is useless on its own: unwrapping it requires the Keystore key, which is
 * bound to this device and this app's signature, and cannot be copied off either.
 *
 * ## Why not androidx.security:security-crypto
 *
 * Because it is deprecated. `EncryptedSharedPreferences`, `EncryptedFile` and `MasterKey` were
 * all deprecated in June 2025 (1.1.0-beta01) in favour of platform APIs and direct use of the
 * Keystore — which is what this class does. It is still the first result for every "encrypt
 * Android data" search, and the version catalog for this project had a `security-crypto` entry
 * pencilled in from the planning phase, written before the deprecation landed. Removing it was
 * part of Day 16. See DECISION_LOG 10.2.
 *
 * ## setUnlockedDeviceRequired, and what it costs
 *
 * On API 28 and above the wrapping key is unusable while the device is locked, so the database
 * cannot be opened then. That is the protection being bought — and it is not free. A sync pass
 * that WorkManager starts on a locked handset, in a process that was not already running, will
 * fail to open the database and must retry rather than treat it as an error. See
 * [LocalStoreUnavailableException] and DECISION_LOG 10.4.
 *
 * Below API 28 the flag does not exist and the key is usable whenever the app runs. minSdk here
 * is 24, so that is a real population, and the honest statement is that those devices get
 * Keystore-bound encryption without the locked-device guarantee — not that they are unprotected.
 *
 * `setIsStrongBoxBacked` was considered and not adopted: some devices advertise StrongBox and
 * then throw at key-generation or first use, so it needs a fallback path that would have to be
 * exercised on hardware this project has no access to. Untested fallback code in the one place
 * that can make every record unreadable is a worse trade than the marginal hardening.
 */
@Singleton
class DatabasePassphrase @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val lock = Any()

    /**
     * The passphrase, generating and storing one on first use.
     *
     * The returned array is handed to SQLCipher's factory, which keeps it for the life of the
     * process — see `DatabaseModule` for why it is not zeroed afterwards.
     *
     * @throws LocalStoreUnavailableException when the device is locked, or when the Keystore
     * key has gone while the wrapped passphrase remains.
     */
    fun get(): ByteArray = synchronized(lock) {
        try {
            resolve()
        } catch (e: GeneralSecurityException) {
            // Covers the locked-device case: using an unlockedDeviceRequired key on a locked
            // handset surfaces as an IllegalBlockSizeException wrapping a KeyStoreException.
            throw LocalStoreUnavailableException("The database passphrase could not be unwrapped", e)
        } catch (e: IOException) {
            throw LocalStoreUnavailableException("The Android Keystore could not be opened", e)
        }
    }

    private fun resolve(): ByteArray {
        val wrapped = storedBlob()
        val key = wrappingKey()

        return when {
            // First run, or a run whose first attempt died before the commit below. Either
            // way there is no durable wrapped passphrase, so no readable database can exist.
            wrapped == null -> create()

            // The wrapped passphrase survived and the key that would unwrap it did not. No
            // code anywhere can read this database again. Say so, rather than minting a fresh
            // passphrase and reporting the file as corrupt — which points the next person at
            // the database instead of at the key.
            key == null -> throw LocalStoreUnavailableException(
                "The Keystore key is gone but a wrapped passphrase remains; the database cannot be decrypted",
            )

            else -> unwrap(wrapped, key)
        }
    }

    private fun create(): ByteArray {
        val passphrase = ByteArray(PASSPHRASE_BYTES).also(SecureRandom()::nextBytes)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, newWrappingKey())
        // GCM needs its IV to decrypt and the IV is not secret, so it is stored in front of
        // the ciphertext rather than alongside it. The Keystore generates it — the cipher is
        // initialised without one, because randomized encryption is required by default and
        // supplying our own would be the beginning of reusing one.
        val blob = cipher.iv + cipher.doFinal(passphrase)

        // commit(), not apply(). apply() writes asynchronously, so a process death in the
        // window before it lands would leave a database encrypted with a passphrase nothing
        // has recorded — permanently unreadable, on the very first run. Blocking the caller
        // once, at first launch, is the correct price.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WRAPPED_PASSPHRASE, Base64.encodeToString(blob, Base64.NO_WRAP))
            .commit()

        return passphrase
    }

    private fun unwrap(blob: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES),
        )
        return cipher.doFinal(blob, GCM_IV_BYTES, blob.size - GCM_IV_BYTES)
    }

    private fun storedBlob(): ByteArray? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_WRAPPED_PASSPHRASE, null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) }

    private fun wrappingKey(): SecretKey? =
        KeyStore.getInstance(ANDROID_KEYSTORE)
            .apply { load(null) }
            .getKey(KEY_ALIAS, null) as? SecretKey

    private fun newWrappingKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            // GCM is a stream mode; padding would be meaningless and the Keystore rejects it.
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setUnlockedDeviceRequired(true)
                }
            }
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /**
         * Namespaced, because a Keystore alias is global to the app. A collision with another
         * component's alias would have one of them silently using the other's key.
         */
        const val KEY_ALIAS = "com.astracare.db.passphrase.v1"

        /**
         * A separate preferences file from anything the app might later store, so that
         * clearing user settings cannot take the wrapped passphrase with it.
         *
         * Plain `SharedPreferences` rather than an encrypted wrapper is the point, not a
         * shortcut: what is written here is already ciphertext, and wrapping it again with a
         * key from the same Keystore would add a step without adding a secret.
         */
        const val PREFS_NAME = "astracare_crypto"
        const val KEY_WRAPPED_PASSPHRASE = "db_passphrase_wrapped"

        /** 256 bits of entropy, which is what SQLCipher derives its key from. */
        const val PASSPHRASE_BYTES = 32
        const val KEY_BITS = 256

        /** 96 bits — the IV length GCM is specified for, and what the Keystore generates. */
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
