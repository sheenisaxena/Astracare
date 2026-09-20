package com.astracare.di

import com.astracare.core.data.di.DataModule
import com.astracare.core.data.remote.MockRemoteBeneficiarySource
import com.astracare.core.data.repository.RoomDraftRepository
import com.astracare.core.data.repository.RoomSyncStateRepository
import com.astracare.core.domain.remote.RemoteBeneficiarySource
import com.astracare.core.domain.repository.BeneficiaryRepository
import com.astracare.core.domain.repository.DraftRepository
import com.astracare.core.domain.repository.SyncStateRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn

/**
 * Replaces the production [DataModule] for instrumented tests.
 *
 * `@TestInstallIn(replaces = [DataModule::class])` removes the real module from the graph and
 * substitutes this one, so every injection point receives [FakeBeneficiaryRepository] without
 * a single production class being aware a test is running.
 *
 * ## Why this beats the alternatives
 *
 * The usual approaches are worse in specific ways:
 *
 * - A debug/test build variant with a different implementation means the code under test is
 *   not the code that ships.
 * - A settable global (`Repository.instance = fake`) leaks between tests and makes them
 *   order-dependent — the hardest kind of flakiness to diagnose.
 * - Constructor-injecting the fake by hand works for unit tests, but not for a UI test that
 *   launches a real Activity: nothing there is constructing the graph by hand.
 *
 * `@TestInstallIn` applies to the whole test source set. For a single test needing a different
 * binding, `@UninstallModules` plus `@BindValue` on that class is the finer-grained tool.
 *
 * This is also why `:app` depends on `:core:data`: the module being replaced must be visible.
 *
 * ## Replacing a module means replacing all of it
 *
 * The three bindings below are the real production ones, restated. That looks redundant and is
 * not: `replaces = [DataModule::class]` deletes **every** binding in that module, not only the
 * one being faked, so anything it declared and this file does not is simply missing from the
 * test graph. Only [BeneficiaryRepository] is actually being substituted; the rest are here to
 * put back what the replacement removed.
 *
 * This surfaced on Day 14, when `SyncStateRepository` became the third such binding and the
 * first two turned out never to have been restored. The instrumented source set is excluded
 * from CI (DECISION_LOG 4.5), so nothing had been compiling this graph. The narrower tool —
 * `@UninstallModules` with `@BindValue` on the one test that needs the fake — does not have
 * this failure mode, and is the right answer if this list grows again.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DataModule::class],
)
abstract class TestDataModule {

    @Binds
    abstract fun bindsFakeBeneficiaryRepository(
        fake: FakeBeneficiaryRepository,
    ): BeneficiaryRepository

    @Binds
    abstract fun bindsDraftRepository(repository: RoomDraftRepository): DraftRepository

    @Binds
    abstract fun bindsSyncStateRepository(repository: RoomSyncStateRepository): SyncStateRepository

    @Binds
    abstract fun bindsRemoteBeneficiarySource(
        source: MockRemoteBeneficiarySource,
    ): RemoteBeneficiarySource
}
