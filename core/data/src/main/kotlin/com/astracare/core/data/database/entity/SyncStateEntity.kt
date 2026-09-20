package com.astracare.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The sync engine's own bookkeeping: where the last pull got to.
 *
 * ## Why this is in the database and not DataStore
 *
 * One nullable String would fit in DataStore comfortably, and it is still the wrong home. The
 * cursor has to stay consistent with the rows it describes — a cursor that survives a database
 * restore while the records do not means the server believes this device is up to date on data
 * it no longer holds, and it will never resend it. Keeping both in one file means one backup,
 * one restore, one encryption boundary when that work lands, and no window in which the two
 * disagree. The same argument as `capture_draft` on Day 11, with a sharper failure mode.
 *
 * ## Why not a column on `beneficiaries`
 *
 * Because it is not a property of any beneficiary. Hanging it off an arbitrary row — or
 * recomputing it as `MAX(updated_at)` — is the shortcut that reintroduces the timestamp
 * comparison `SyncCursor` exists to avoid.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(

    /**
     * Fixed at [SYNC_STATE_ROW_ID]. There is one sync engine, so there is one row, and the
     * primary key is what enforces that rather than discipline at each call site.
     */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int = SYNC_STATE_ROW_ID,

    /**
     * The opaque cursor from the last fully applied pull, or null if this device has never
     * completed one.
     *
     * Nullable rather than defaulting to an empty string: "never pulled" and "pulled, and the
     * server's cursor happens to be empty" are different states, and conflating them would ask
     * the server for its entire history at a moment it thought the device was current.
     */
    @ColumnInfo(name = "pull_cursor")
    val pullCursor: String?,
)

/** There is one sync engine, so there is one row. */
const val SYNC_STATE_ROW_ID: Int = 1
