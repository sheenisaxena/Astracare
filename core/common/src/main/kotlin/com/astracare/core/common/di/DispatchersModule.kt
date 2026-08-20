package com.astracare.core.common.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Provides the app's coroutine dispatchers.
 *
 * Moved here from `:app` (the Day 2 bootstrap location). It matters that it lives in
 * `:core:common`:
 *
 * A binding declared in `:app` does work at runtime — `SingletonComponent` is assembled in
 * `:app`, so anything injected anywhere can reach it. But it inverts the dependency direction
 * conceptually: `:core:data` would rely on a binding defined in a module it does not and must
 * not depend on. Any module extracted from this app, or reused in another, would take its
 * dispatcher contract with it only if that contract lives at the bottom of the graph.
 *
 * `@InstallIn(SingletonComponent::class)` because dispatchers are stateless and shared for the
 * whole process — creating them per screen would be pointless allocation.
 *
 * Note this module carries Dagger annotations while remaining a pure Kotlin module: the
 * `hilt-core` artifact provides them with no Android dependency. See the `astracare.jvm.hilt`
 * convention plugin.
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
