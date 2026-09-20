package com.astracare.core.domain.access

import com.astracare.core.model.UserRole

/**
 * What each role may do, as a domain declaration rather than a scattering of `if` statements.
 *
 * Same shape as `RecordAttentionOrder`, and for the same reason: this is a product rule, it
 * will be argued about, and it should be readable in one place by someone who does not read
 * Kotlin fluently. The UI consults it to decide what to show; the use cases consult it to
 * decide what to do. Neither contains a copy of it.
 *
 * ## The nested `when` is the guard
 *
 * Both levels are exhaustive with no `else`. Adding a [UserRole] or a [Capability] therefore
 * fails the build here, at the one place that has to have an opinion, rather than silently
 * defaulting — which for a permission check means silently defaulting to *something*, and
 * whichever default was chosen would be wrong half the time. `RolePermissionsTest` pins the
 * whole matrix so the answers themselves cannot drift unnoticed either.
 *
 * ## What this is not
 *
 * It is not a security boundary, and DECISION_LOG 4.3 said so before any of it was written.
 * The role lives on the device, the device belongs to the user, and the user can change it.
 * A real deployment enforces this server-side on every request; this decides what the app
 * offers, which is a UX concern and a correctness one.
 *
 * That still leaves it worth enforcing below the UI — see `SaveBeneficiaryUseCase`. Hiding a
 * button and refusing the action are different guarantees, and the second one is what keeps
 * the app from performing something its own interface says is unavailable.
 */
object RolePermissions {

    fun allows(role: UserRole, capability: Capability): Boolean = when (role) {
        UserRole.FIELD_WORKER -> when (capability) {
            Capability.CAPTURE_RECORD -> true
            Capability.VIEW_RECORDS -> true
            // A worker reading the trail of their own actions weakens it as a control, and
            // there is no task they need it for.
            Capability.VIEW_AUDIT_TRAIL -> false
        }

        UserRole.SUPERVISOR -> when (capability) {
            // Not a restriction for its own sake: a supervisor typing a record in someone
            // else's name is the attribution problem the trail exists to answer.
            Capability.CAPTURE_RECORD -> false
            Capability.VIEW_RECORDS -> true
            Capability.VIEW_AUDIT_TRAIL -> true
        }
    }
}

/**
 * The things a role can be permitted to do.
 *
 * One entry per capability the app actually has. Inventing a permission for a feature that
 * does not exist produces a matrix nobody can check against behaviour — the fastest way to a
 * permission model that is confidently wrong.
 */
enum class Capability {

    /** Open the capture form and save a record. */
    CAPTURE_RECORD,

    /** See the history list. Held by both roles; present so the matrix is complete. */
    VIEW_RECORDS,

    /** Read the append-only audit trail. */
    VIEW_AUDIT_TRAIL,
}
