package com.astracare.core.model

/**
 * One thing that happened, written down and never changed.
 *
 * ## What an entry can and cannot tell you
 *
 * [actor] is a [UserRole], not a person, because this app has no authentication. An entry
 * therefore says "this device, in supervisor mode, marked record X as conflicted at time T" —
 * which is genuinely useful for reconstructing what the app did, and is **not** an
 * accountability record. Anyone can change the active role, and the change is itself logged
 * ([AuditAction.ROLE_CHANGED]) precisely because that is the one mitigation available without
 * a server.
 *
 * Saying so plainly is the point. An audit trail presented as more than it is does more harm
 * than none at all, because it invites someone to rely on it. See DECISION_LOG 11.5.
 *
 * ## Why the timestamp is not evidence either
 *
 * [at] comes from the device clock, which DECISION_LOG 2.6 has recorded as untrustworthy since
 * Day 2. It orders events on one handset well enough to read a history; it will not survive
 * someone setting the clock back.
 */
data class AuditEntry(
    /** Assigned by the store on insert. Monotonic, so it orders entries when [at] cannot. */
    val id: Long,
    val actor: UserRole,
    val action: AuditAction,
    /** The record this concerns, or null for something that concerns the device itself. */
    val recordId: BeneficiaryId?,
    val at: Timestamp,
)

/**
 * The kinds of event worth recording.
 *
 * Deliberately short. An audit log that records everything is a log nobody reads, and the
 * things worth writing down here are the ones that change a health record or change who is
 * allowed to:
 *
 *  - the two that alter clinical data,
 *  - the one that means two versions of a record disagreed and the app stopped,
 *  - and the one that changes what this device is permitted to do.
 *
 * Successful syncs are excluded on purpose: `SyncStatus` on the record already carries that,
 * and duplicating it here would bury the four events above under a line per record per pass.
 */
enum class AuditAction {

    /** A record existed nowhere on this device and now does. */
    RECORD_CREATED,

    /** A record that already existed was changed. */
    RECORD_UPDATED,

    /**
     * A pull found a server version that disagreed with an unsent local edit, and refused to
     * choose. The most audit-worthy event in the app: it is the only one where data on two
     * sides diverged and a person has to decide.
     */
    CONFLICT_DETECTED,

    /**
     * The active role changed. Logged because the role is switchable by whoever is holding the
     * phone — so a trail that did not record switches could be made to attribute a worker's
     * action to a supervisor, or the reverse, with no trace.
     */
    ROLE_CHANGED,
}
