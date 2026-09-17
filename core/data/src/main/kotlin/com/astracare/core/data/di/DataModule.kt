package com.astracare.core.data.di

import com.astracare.core.data.repository.OfflineFirstBeneficiaryRepository
import com.astracare.core.data.repository.RoomDraftRepository
import com.astracare.core.domain.repository.BeneficiaryRepository
import com.astracare.core.domain.repository.DraftRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds domain interfaces to their `:core:data` implementations.
 *
 * This module is the single seam between the domain layer and persistence. It is the only
 * place in the app that knows which implementation of [BeneficiaryRepository] is in use.
 *
 * That claim has now been tested: replacing the temporary in-memory implementation with the
 * Room-backed [OfflineFirstBeneficiaryRepository] changed exactly this one line. Nothing in
 * `:core:domain`, `:feature:patients` or `:app` was touched — which is the whole return on
 * depending on an interface rather than a class.
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
        repository: OfflineFirstBeneficiaryRepository,
    ): BeneficiaryRepository

    /**
     * Drafts get their own binding rather than being folded into the repository above.
     *
     * They look similar enough to merge and must not be: records are pushed to a server,
     * drafts must never be. One repository would mean one `observeAll()` that has to remember
     * to exclude drafts, and the first time anyone forgot, a half-typed record would sync.
     */
    @Binds
    abstract fun bindsDraftRepository(repository: RoomDraftRepository): DraftRepository
}
