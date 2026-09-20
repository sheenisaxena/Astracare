package com.astracare.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Which role this device is operating as.
 *
 * ## Why a third single-row table rather than a column on `sync_state`
 *
 * Because it is not sync state, and `SyncStateEntity`'s own documentation rejects exactly this
 * shortcut for exactly this reason. The two have different lifetimes: clearing every record
 * and resetting the pull cursor is a coherent "start again" operation that must not change who
 * the device thinks it is, and switching role must not disturb the cursor. Sharing a row would
 * couple them so that any future operation touching one has to think about the other.
 *
 * ## Why not SharedPreferences
 *
 * Day 16 put the wrapped database passphrase in preferences and gave a specific reason: it has
 * to be readable *before* the database can open. Nothing about a role needs that, so the Day 11
 * argument applies unchanged — the app already has a database, a migration story and a backup
 * policy, and a second mechanism means a second thing to migrate and a second place to look.
 */
@Entity(tableName = "session")
data class SessionEntity(

    /** One device, one active role. Enforced by the key rather than by discipline. */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int = SESSION_ROW_ID,

    /**
     * The [com.astracare.core.model.UserRole] name. Decoded defensively — an unrecognised
     * value falls back to the default role rather than throwing, because a crash loop on
     * launch would trap a health worker's unsynced records inside the database.
     */
    @ColumnInfo(name = "active_role")
    val activeRole: String,
)

/** There is one device, so there is one session. */
const val SESSION_ROW_ID: Int = 1
