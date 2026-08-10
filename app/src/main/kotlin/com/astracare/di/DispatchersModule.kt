package com.astracare.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * First module in the Hilt graph — provides the app's coroutine dispatchers.
 *
 * Installed in [SingletonComponent] because dispatchers are stateless and shared for the
 * process lifetime.
 *
 * Known limitation: this module belongs in `:core:common` (via the `hilt-core` artifact, which
 * carries the Dagger annotations without any Android dependency) so that every module can
 * inject dispatchers without depending on `:app`. It lives here only while the DI graph is
 * being bootstrapped; see docs/DECISION_LOG.md section 2.5.
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {

    @Provides
    @Dispatcher(AstraCareDispatcher.IO)
    fun providesIODispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Dispatcher(AstraCareDispatcher.Default)
    fun providesDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
