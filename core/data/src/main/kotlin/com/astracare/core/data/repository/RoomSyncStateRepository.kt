package com.astracare.core.data.repository

import com.astracare.core.common.di.AstraCareDispatcher
import com.astracare.core.common.di.Dispatcher
import com.astracare.core.data.database.dao.SyncStateDao
import com.astracare.core.data.database.entity.SyncStateEntity
import com.astracare.core.domain.remote.SyncCursor
import com.astracare.core.domain.repository.SyncStateRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [SyncStateRepository].
 *
 * Nothing here catches exceptions, and that is the deliberate opposite of [RoomDraftRepository],
 * which logs and carries on. A draft that fails to save costs a few keystrokes. A cursor that
 * fails to save and is treated as saved means the next pull asks the server to resume from a
 * position the device never reached, and every record in between is skipped for good. There is
 * no safe way to continue past that, so the failure propagates and the sync pass is retried —
 * which is exactly right, because re-pulling from the old cursor is free.
 */
@Singleton
class RoomSyncStateRepository @Inject constructor(
    private val dao: SyncStateDao,
    @Dispatcher(AstraCareDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : SyncStateRepository {

    override suspend fun lastPullCursor(): SyncCursor? = withContext(ioDispatcher) {
        dao.load()?.pullCursor?.let(::SyncCursor)
    }

    override suspend fun recordPullCursor(cursor: SyncCursor) {
        withContext(ioDispatcher) {
            dao.upsert(SyncStateEntity(pullCursor = cursor.value))
        }
    }
}
