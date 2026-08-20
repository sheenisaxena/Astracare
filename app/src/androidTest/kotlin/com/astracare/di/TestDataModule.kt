package com.astracare.di

import com.astracare.core.data.di.DataModule
import com.astracare.core.domain.repository.BeneficiaryRepository
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
}
