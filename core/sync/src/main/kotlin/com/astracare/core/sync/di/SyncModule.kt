package com.astracare.core.sync.di

import android.content.Context
import androidx.work.WorkManager
import com.astracare.core.domain.sync.SyncScheduler
import com.astracare.core.sync.WorkManagerSyncScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the sync engine into the graph.
 *
 * Split into two modules because `@Binds` and `@Provides` cannot live in the same class —
 * `@Binds` needs an abstract class, `@Provides` needs a concrete one.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {

    /**
     * The single line that connects `:core:data` to `:core:sync` without either depending on
     * the other. `OfflineFirstBeneficiaryRepository` asks for a [SyncScheduler]; this decides
     * it is the WorkManager one.
     */
    @Binds
    abstract fun bindsSyncScheduler(scheduler: WorkManagerSyncScheduler): SyncScheduler
}

@Module
@InstallIn(SingletonComponent::class)
object WorkManagerModule {

    /**
     * `WorkManager.getInstance` rather than constructing one.
     *
     * WorkManager is a process singleton that owns its own database and thread pool; the
     * instance must come from the framework. Providing it through Hilt anyway is what lets
     * [WorkManagerSyncScheduler] take it as a constructor parameter and be unit tested against
     * a fake or `WorkManagerTestInitHelper`, instead of reaching for a static inside its
     * methods.
     *
     * `@ApplicationContext`, not an Activity context: this outlives every screen.
     */
    @Provides
    @Singleton
    fun providesWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
