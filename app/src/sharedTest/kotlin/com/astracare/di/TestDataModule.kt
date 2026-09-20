package com.astracare.di

import com.astracare.core.data.di.DataModule
import com.astracare.core.domain.remote.RemoteBeneficiarySource
import com.astracare.core.domain.repository.AuditRepository
import com.astracare.core.domain.repository.BeneficiaryRepository
import com.astracare.core.domain.repository.DraftRepository
import com.astracare.core.domain.repository.SessionRepository
import com.astracare.core.domain.repository.SyncStateRepository
import com.astracare.core.domain.sync.SyncScheduler
import com.astracare.core.sync.di.SyncModule
import com.astracare.core.sync.di.WorkManagerModule
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn

/**
 * Replaces the whole data layer for UI tests.
 *
 * `@TestInstallIn(replaces = [DataModule::class])` removes the real module from the graph and
 * substitutes this one, so every injection point receives a fake without a single production
 * class being aware a test is running.
 *
 * ## Why this beats the alternatives
 *
 * - A debug/test build variant with a different implementation means the code under test is
 *   not the code that ships.
 * - A settable global (`Repository.instance = fake`) leaks between tests and makes them
 *   order-dependent — the hardest kind of flakiness to diagnose.
 * - Constructor-injecting the fake by hand works for unit tests, but not for a UI test that
 *   launches a real Activity: nothing there is constructing the graph by hand.
 *
 * ## Why everything is faked now, and not just the repository
 *
 * Until Day 20 this module faked `BeneficiaryRepository` and restored the real Room-backed
 * implementations for the rest. That worked on a device and cannot work on the JVM: since Day
 * 16 the database is opened by SQLCipher, which is a **native** library, and Robolectric cannot
 * load a `.so`. The first screen to ask for a `DraftRepository` would take the whole test down
 * with an `UnsatisfiedLinkError` that names neither the database nor the test.
 *
 * So the division is now explicit and worth stating as a rule: **UI tests run against fakes;
 * the real database is tested directly.** `MigrationTest`, `BeneficiaryDaoTest` and
 * `EncryptedDatabaseTest` cover Room, the SQL and the encryption against a real device. Nothing
 * is uncovered by faking here — it is covered somewhere a fake cannot mislead.
 *
 * This source set is shared between `test` and `androidTest` (see `app/build.gradle.kts`), so
 * the same doubles serve the Robolectric run and a device run.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DataModule::class],
)
abstract class TestDataModule {

    @Binds
    abstract fun bindsBeneficiaryRepository(fake: FakeBeneficiaryRepository): BeneficiaryRepository

    @Binds
    abstract fun bindsDraftRepository(fake: FakeDraftRepository): DraftRepository

    @Binds
    abstract fun bindsSessionRepository(fake: FakeSessionRepository): SessionRepository

    @Binds
    abstract fun bindsAuditRepository(fake: FakeAuditRepository): AuditRepository

    @Binds
    abstract fun bindsSyncStateRepository(fake: FakeSyncStateRepository): SyncStateRepository

    @Binds
    abstract fun bindsRemoteBeneficiarySource(fake: FakeRemoteBeneficiarySource): RemoteBeneficiarySource
}

/**
 * Replaces the sync engine's scheduling.
 *
 * `WorkManagerSyncScheduler` takes a `WorkManager`, which is a process singleton that
 * initialises itself against a real `Context` and its own database. A UI test that saves a
 * record triggers `requestSync()`, and with the real binding in place that call is the first
 * thing to fail — for reasons entirely unrelated to the screen under test.
 *
 * Both modules are replaced: `SyncModule` binds the scheduler, `WorkManagerModule` provides the
 * `WorkManager` it needs. Replacing only the first leaves an unused provider that still has to
 * resolve.
 *
 * Whether a save *requests* a sync is tested where it can be asserted rather than merely
 * survived — `SyncSchedulingTest` in `:core:data`, with MockK.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [SyncModule::class, WorkManagerModule::class],
)
abstract class TestSyncModule {

    @Binds
    abstract fun bindsSyncScheduler(fake: NoOpSyncScheduler): SyncScheduler
}
