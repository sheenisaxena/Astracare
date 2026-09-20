package com.astracare.core.data.repository

import com.astracare.core.common.di.AstraCareDispatcher
import com.astracare.core.common.di.Dispatcher
import com.astracare.core.data.database.dao.SessionDao
import com.astracare.core.data.database.entity.SessionEntity
import com.astracare.core.domain.repository.SessionRepository
import com.astracare.core.model.UserRole
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [SessionRepository].
 *
 * A device that has never chosen a role, and a device whose stored role this build does not
 * recognise, both resolve to [UserRole.Default]. That is the lower-privilege role, so every
 * unreadable state fails toward less access rather than more — the one direction a permission
 * default can fail safely.
 */
@Singleton
class RoomSessionRepository @Inject constructor(
    private val dao: SessionDao,
    @Dispatcher(AstraCareDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : SessionRepository {

    override fun observeActiveRole(): Flow<UserRole> =
        dao.observe().map { it.toRole() }

    override suspend fun activeRole(): UserRole =
        withContext(ioDispatcher) { dao.load().toRole() }

    override suspend fun setActiveRole(role: UserRole) {
        withContext(ioDispatcher) { dao.upsert(SessionEntity(activeRole = role.name)) }
    }
}

private fun SessionEntity?.toRole(): UserRole =
    this?.let { stored -> UserRole.entries.firstOrNull { it.name == stored.activeRole } }
        ?: UserRole.Default
