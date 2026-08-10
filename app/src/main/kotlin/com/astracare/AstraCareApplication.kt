package com.astracare

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point and the root of the Hilt dependency graph.
 *
 * [HiltAndroidApp] triggers code generation for the `SingletonComponent`, which every
 * other `@AndroidEntryPoint` in the app resolves against. Without this annotation on a
 * registered [Application] subclass, injection fails at runtime rather than compile time.
 *
 * Registered in AndroidManifest.xml via `android:name=".AstraCareApplication"`.
 */
@HiltAndroidApp
class AstraCareApplication : Application()
