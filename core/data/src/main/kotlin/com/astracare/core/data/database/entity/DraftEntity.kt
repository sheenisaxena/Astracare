package com.astracare.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The single in-progress capture, stored so it survives the handset dying.
 *
 * ## Why every measurement column is TEXT
 *
 * `beneficiaries` stores weight as `REAL` because a record's weight is a number. This table
 * stores it as `TEXT` because a *draft's* weight is whatever the health worker has typed so
 * far, and "12." is not a number. Coercing it at the storage layer would mean the draft that
 * comes back is not the draft that was saved — the cursor would jump, or a half-typed decimal
 * would silently become a whole one.
 *
 * The two tables disagreeing about the type of "weight" is correct: they are storing
 * different things. See `CaptureDraft` in `:core:model`.
 *
 * ## Why a single-row table rather than SharedPreferences or DataStore
 *
 * The app already has a database, a migration story, and a backup rule covering it. A second
 * persistence mechanism for one object means a second thing to migrate, a second thing to
 * encrypt when the security work lands on Day 16, and a second place to look when data goes
 * missing. See DECISION_LOG 6.3.
 */
@Entity(tableName = "capture_draft")
data class DraftEntity(

    /**
     * Fixed at [SINGLE_ROW_ID].
     *
     * The single-row constraint is enforced by the primary key rather than by discipline:
     * every write is an upsert against the same key, so a second draft is not something the
     * code has to avoid creating — it is something SQLite will not allow.
     */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int = SINGLE_ROW_ID,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "age_years")
    val ageYears: String,

    @ColumnInfo(name = "village")
    val village: String,

    @ColumnInfo(name = "weight_kg")
    val weightKg: String,

    @ColumnInfo(name = "height_cm")
    val heightCm: String,

    @ColumnInfo(name = "muac_mm")
    val muacMm: String,

    /**
     * When the draft was last written.
     *
     * Not read by anything today. It is here because the first question asked of a restored
     * draft will be "how old is this?" — a form abandoned three weeks ago should probably be
     * offered differently from one abandoned during a power cut ten minutes ago. Adding the
     * column now costs one INTEGER per row; adding it later costs a migration.
     */
    @ColumnInfo(name = "updated_at")
    val updatedAtEpochMillis: Long,
)

/** There is one capture screen, so there is one draft. */
const val SINGLE_ROW_ID: Int = 1
