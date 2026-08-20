package com.astracare.core.common.di

import com.astracare.core.common.time.SystemTimeProvider
import com.astracare.core.common.time.TimeProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the app's source of "now".
 *
 * Exists for the same reason [DispatchersModule] does: a static `System.currentTimeMillis()`
 * inside a use case cannot be replaced, so any behaviour depending on the clock becomes
 * untestable without sleeping. Sync conflict resolution is *entirely* timestamp comparison,
 * which is exactly why the clock is a dependency here rather than a static call.
 *
 * `@Provides` rather than `@Binds` because [SystemTimeProvider] has no `@Inject` constructor —
 * it is constructed here. If it later needed injected dependencies of its own, `@Binds` would
 * be the cheaper choice.
 */
@Module
@InstallIn(SingletonComponent::class)
object TimeModule {

    @Provides
    @Singleton
    fun providesTimeProvider(): TimeProvider = SystemTimeProvider()
}
