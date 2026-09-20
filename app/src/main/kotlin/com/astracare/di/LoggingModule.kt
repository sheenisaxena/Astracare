package com.astracare.di

import com.astracare.core.common.log.Logger
import com.astracare.log.AndroidLogger
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the only logging implementation that knows what logcat is.
 *
 * In `:app` rather than in `:core:common`, because that module is Kotlin/JVM and could not host
 * an Android-backed implementation even if it wanted to. The consequence is the useful part: a
 * core module that injects a [Logger] gets one at runtime and a fake in its tests, and nothing
 * below `:app` compiles against the Android framework to do it.
 *
 * There is deliberately no fallback binding anywhere else. A missing implementation is a Hilt
 * compile error, not an app that silently logs nothing.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LoggingModule {

    @Binds
    abstract fun bindsLogger(logger: AndroidLogger): Logger
}
