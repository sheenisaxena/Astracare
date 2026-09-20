package com.astracare.core.model

/**
 * Who is using this handset.
 *
 * ## This is not an identity
 *
 * There is no authentication in this app, so a role is not a claim about *who* someone is — it
 * is a statement about which affordances the app is currently presenting. The person holding
 * the phone can change it, and the UI that lets them do so is labelled as the stand-in it is.
 *
 * That matters most for the audit trail. An audit entry records the role that was active, not
 * a person, so it answers "what did this device do, in which mode" and not "who did this".
 * Those are different questions and only the second one is what an audit log usually means.
 * The distinction is written into `AuditEntry` and DECISION_LOG 11.5 rather than left for a
 * reviewer to discover.
 *
 * ## Two roles, because two is what the app can currently express
 *
 * A third role would need a third capability to justify it. The capabilities that exist are
 * capture, review and read-the-audit-trail, and those split cleanly in two. See
 * `RolePermissions` in `:core:domain`, which is where what a role may *do* is decided — this
 * enum deliberately carries no permissions of its own, so adding a capability is one edit in
 * one file rather than a new method on every role.
 */
enum class UserRole {

    /**
     * Captures records in the field. Cannot read the audit trail — a log that the person being
     * logged can read and act on is a weaker control, and there is no operational reason a
     * worker needs it.
     */
    FIELD_WORKER,

    /**
     * Reviews what has been captured and reads the audit trail. Cannot capture: a supervisor
     * entering records in someone else's name is exactly the attribution problem the audit
     * trail exists to answer.
     */
    SUPERVISOR,

    ;

    companion object {
        /**
         * What a device starts as.
         *
         * The lower-privilege role, so that a fresh install or an unreadable stored value
         * fails toward less access rather than more. It is also the common case — most
         * handsets in a deployment like this are held by the people doing the capturing.
         */
        val Default = FIELD_WORKER
    }
}
