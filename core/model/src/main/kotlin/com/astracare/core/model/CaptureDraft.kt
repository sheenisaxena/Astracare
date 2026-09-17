package com.astracare.core.model

/**
 * A beneficiary capture that has been started but not committed.
 *
 * ## Why this is a domain model and not UI state
 *
 * Every field is a raw `String`, which looks at first like UI state that has escaped into the
 * domain. It is the opposite: a draft is a distinct domain concept from a [Beneficiary], and
 * conflating them is what would break the layering.
 *
 * A [Beneficiary] is a *valid, syncable record*. Its age is an `Int` because a record with an
 * age of "abc" is not a record. A draft is *unfinished work that must survive the handset
 * dying* — it is explicitly allowed to be invalid, half-typed, and internally inconsistent,
 * because that is the state a health worker's form is in when the battery goes. Forcing it
 * through the [Beneficiary] type would mean either rejecting the save (losing the work, which
 * is the entire thing this exists to prevent) or inventing placeholder values (silently
 * fabricating clinical data).
 *
 * So the raw strings are not a leak; they are the point. The type says "this has not been
 * validated", and nothing downstream can mistake it for a record.
 *
 * ## Why there is exactly one
 *
 * One capture screen is an explicit scope boundary of this project, so there is one draft, in
 * a single-row table. If a second capture flow ever exists this gains a key — a schema
 * migration, not a redesign.
 */
data class CaptureDraft(
    val name: String,
    val ageYears: String,
    val village: String,
    val weightKg: String,
    val heightCm: String,
    val muacMm: String,
) {

    /**
     * True when the health worker has typed nothing, or has cleared everything back out.
     *
     * Drives the one real rule about drafts: a blank draft is not persisted, and an existing
     * draft that becomes blank is deleted. Storing a row of empty strings would mean the next
     * launch "restores" a form the user deliberately emptied, and the capture screen would
     * never again start genuinely fresh.
     */
    val isBlank: Boolean
        get() = name.isBlank() &&
            ageYears.isBlank() &&
            village.isBlank() &&
            weightKg.isBlank() &&
            heightCm.isBlank() &&
            muacMm.isBlank()

    companion object {
        val Empty = CaptureDraft(
            name = "",
            ageYears = "",
            village = "",
            weightKg = "",
            heightCm = "",
            muacMm = "",
        )
    }
}
