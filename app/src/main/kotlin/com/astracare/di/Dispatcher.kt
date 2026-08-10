package com.astracare.di

import javax.inject.Qualifier

/**
 * Qualifies an injected [kotlinx.coroutines.CoroutineDispatcher].
 *
 * Dispatchers are injected rather than referenced directly as `Dispatchers.IO` so that
 * tests can substitute a `TestDispatcher` and control virtual time. Hard-coding
 * `Dispatchers.IO` inside a repository is the single most common reason coroutine tests
 * become flaky — the test has no way to await work it cannot see.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val type: AstraCareDispatcher)

enum class AstraCareDispatcher {
    /** Disk and network I/O — Room reads, sync calls. */
    IO,

    /** CPU-bound work — parsing, mapping, conflict resolution. */
    Default,
}
