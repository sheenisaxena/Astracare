package com.astracare.core.data.di

import com.astracare.core.data.repository.InMemoryBeneficiaryRepository
import com.astracare.core.domain.repository.BeneficiaryRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds domain interfaces to their `:core:data` implementations.
 *
 * This module is the single seam between the domain layer and persistence. It is the only
 * place in the app that knows which implementation of [BeneficiaryRepository] is in use —
 * swapping the in-memory version for the Room-backed one is a one-line change here, and
 * nothing in `:core:domain`, `:feature:patients` or `:app` is touched.
 *
 * ## Why `@Binds` and not `@Provides`
 *
 * `@Binds` states "when something asks for this interface, give it that implementation" and
 * requires an abstract function with a single parameter. Dagger resolves it entirely at
 * compile time and generates no factory method, so it is cheaper than the equivalent
 * `@Provides fun(impl: Foo): Bar = impl`, which generates a real method that runs at runtime
 * to do nothing but return its argument.
 *
 * Rule of thumb: `@Binds` for "this implements that", `@Provides` when you must actually
 * construct or configure something (as in `DispatchersModule`).
 *
 * The module is `abstract` because `@Binds` functions have no body. Dagger writes the
 * implementation.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    abstract fun bindsBeneficiaryRepository(
        // Replaced by OfflineFirstBeneficiaryRepository once Room lands.
        repository: InMemoryBeneficiaryRepository,
    ): BeneficiaryRepository
}
