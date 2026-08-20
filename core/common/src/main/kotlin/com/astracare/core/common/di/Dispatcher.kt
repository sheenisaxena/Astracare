package com.astracare.core.common.di

import javax.inject.Qualifier

/**
 * Qualifies an injected [kotlinx.coroutines.CoroutineDispatcher].
 *
 * Two bindings of the same type (`CoroutineDispatcher`) cannot be told apart by Dagger on
 * type alone, so a qualifier annotation disambiguates them.
 *
 * Dispatchers are injected rather than referenced as `Dispatchers.IO` so tests can substitute
 * a `TestDispatcher` and drive virtual time. Code that hard-codes a dispatcher throws work
 * onto a real thread pool the test cannot see or await — the usual root cause of flaky
 * coroutine tests.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val type: AstraCareDispatcher)

enum class AstraCareDispatcher {
    /** Disk and network I/O — Room reads, sync calls. */
    IO,

    /** CPU-bound work — mapping, parsing, conflict resolution. */
    Default,
}
