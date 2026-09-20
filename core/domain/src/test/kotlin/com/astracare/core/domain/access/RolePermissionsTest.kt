package com.astracare.core.domain.access

import com.astracare.core.model.UserRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The permission matrix, pinned as a table.
 *
 * `RolePermissions` already fails to compile if a role or a capability is added without an
 * answer — both `when`s are exhaustive with no `else`. What the compiler cannot check is
 * whether the answers are the *intended* ones, and a permission silently flipped from false to
 * true is the kind of change that reviews well and ships wrong.
 *
 * So the whole matrix is written out here, every cell, including the boring ones. Six
 * assertions is not much to pay for a rule whose failure mode is a supervisor who can quietly
 * capture records in a field worker's name.
 *
 * The third test is the drift guard, in the spirit of `RecordAttentionOrderTest`: it fails when
 * the matrix stops covering every combination, so adding a role cannot leave a hole that the
 * exhaustive `when` filled with a guess nobody reviewed.
 */
class RolePermissionsTest {

    @Test
    fun `a field worker captures records and cannot read the trail`() {
        assertEquals(true, allows(UserRole.FIELD_WORKER, Capability.CAPTURE_RECORD))
        assertEquals(true, allows(UserRole.FIELD_WORKER, Capability.VIEW_RECORDS))
        // Not an oversight. A trail readable by the person it records is a weaker control,
        // and there is no task a worker needs it for.
        assertEquals(false, allows(UserRole.FIELD_WORKER, Capability.VIEW_AUDIT_TRAIL))
    }

    @Test
    fun `a supervisor reviews and reads the trail but cannot capture`() {
        // The one most likely to be "fixed" by someone who assumes a supervisor can do
        // everything a worker can. They cannot: a supervisor typing a record in someone
        // else's name is the attribution problem the trail exists to answer.
        assertEquals(false, allows(UserRole.SUPERVISOR, Capability.CAPTURE_RECORD))
        assertEquals(true, allows(UserRole.SUPERVISOR, Capability.VIEW_RECORDS))
        assertEquals(true, allows(UserRole.SUPERVISOR, Capability.VIEW_AUDIT_TRAIL))
    }

    @Test
    fun `every role and capability pair is covered by this test`() {
        val asserted = setOf(
            UserRole.FIELD_WORKER to Capability.CAPTURE_RECORD,
            UserRole.FIELD_WORKER to Capability.VIEW_RECORDS,
            UserRole.FIELD_WORKER to Capability.VIEW_AUDIT_TRAIL,
            UserRole.SUPERVISOR to Capability.CAPTURE_RECORD,
            UserRole.SUPERVISOR to Capability.VIEW_RECORDS,
            UserRole.SUPERVISOR to Capability.VIEW_AUDIT_TRAIL,
        )
        val everyPair = UserRole.entries.flatMap { role ->
            Capability.entries.map { capability -> role to capability }
        }.toSet()

        assertEquals(
            "a role or capability was added without a line in the two tests above",
            everyPair,
            asserted,
        )
    }

    @Test
    fun `the default role is the least privileged one`() {
        // Every path that cannot determine a role — a fresh install, a value this build does
        // not recognise, the initial value of the UI's StateFlow — resolves to this one. It
        // must therefore be the role that can do least, so an unreadable state fails toward
        // less access rather than more.
        val default = UserRole.Default
        val others = UserRole.entries - default

        others.forEach { other ->
            val defaultCan = Capability.entries.count { allows(default, it) }
            val otherCan = Capability.entries.count { allows(other, it) }
            assertEquals(
                "$default is not the least privileged role; $other holds fewer capabilities",
                defaultCan,
                minOf(defaultCan, otherCan),
            )
        }
    }

    private fun allows(role: UserRole, capability: Capability) =
        RolePermissions.allows(role, capability)
}
