package com.astracare.core.domain.repository

/**
 * The local store exists but cannot be opened right now.
 *
 * Thrown, not returned as an [com.astracare.core.common.Outcome], and that is deliberate — the
 * project's rule is that *expected* failures are values and this is not one of them. It is
 * raised while the dependency graph is being built, before any repository method has been
 * called, so there is no call for a value to be the result of.
 *
 * ## What actually causes it
 *
 * Since Day 16 the database is encrypted with a passphrase wrapped by an Android Keystore key
 * marked `setUnlockedDeviceRequired`. On a locked device that key cannot be used, so the
 * passphrase cannot be unwrapped and the database cannot be opened. That is the feature
 * working: the data is supposed to be unreadable while the handset is locked.
 *
 * It matters because the sync worker can be started by WorkManager on a locked device, in a
 * process that was not already running. `SyncBeneficiariesWorker` therefore treats this as a
 * retry rather than a failure — nothing is wrong, the moment is simply wrong. See
 * DECISION_LOG 10.4.
 *
 * The other cause is unrecoverable: the Keystore key has gone while the wrapped passphrase
 * remains, so the existing database can never be decrypted by anything. Failing loudly is the
 * only honest response; the alternative is minting a fresh passphrase and reporting a corrupt
 * database, which points the next person at entirely the wrong problem.
 */
class LocalStoreUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
