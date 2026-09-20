package com.astracare

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.astracare.core.domain.sync.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point and the root of the Hilt dependency graph.
 *
 * [HiltAndroidApp] triggers code generation for the `SingletonComponent`, which every
 * other `@AndroidEntryPoint` in the app resolves against. Without this annotation on a
 * registered [Application] subclass, injection fails at runtime rather than compile time.
 *
 * Registered in AndroidManifest.xml via `android:name=".AstraCareApplication"`.
 *
 * ## Why this class implements Configuration.Provider
 *
 * `SyncBeneficiariesWorker` takes an injected dependency, which WorkManager's default factory
 * cannot supply — it only knows how to call a two-argument constructor. [HiltWorkerFactory]
 * can, and WorkManager only uses it if it is handed one at initialisation.
 *
 * That requires disabling WorkManager's automatic startup, which is done in the manifest by
 * removing `WorkManagerInitializer` from `androidx.startup`'s provider. Miss that step and the
 * framework initialises itself first with the default factory, this configuration is never
 * read, and the app crashes the first time a worker runs with "Could not instantiate
 * androidx...SyncBeneficiariesWorker" — a message that points at the worker rather than at the
 * two lines of manifest that actually caused it.
 *
 * The three pieces only work together: the `@HiltWorker` annotation, the KSP processor in
 * `:core:sync`, and this. Any one missing produces a runtime failure, not a build failure.
 */
@HiltAndroidApp
class AstraCareApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /**
     * Injected rather than resolved here, so `:app` never names WorkManager beyond this
     * configuration. Scheduling policy stays in `:core:sync`.
     */
    @Inject
    lateinit var syncScheduler: SyncScheduler

    /**
     * A property, not `getWorkManagerConfiguration()`. WorkManager 2.9 changed
     * `Configuration.Provider` from a method to a `val`; overriding the old signature now
     * compiles as an unrelated function and silently does nothing.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Enqueuing is cheap and asynchronous — it writes a row to WorkManager's own database
        // and returns. Doing it here rather than from a screen means the safety net exists
        // even if the user never navigates anywhere, which is the case it is there for.
        syncScheduler.ensurePeriodicSync()
    }
}
