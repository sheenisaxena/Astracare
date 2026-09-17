package com.astracare.core.designsystem.component

/**
 * How urgent a piece of status is. The component's only semantic input.
 *
 * ## Why this is not `SyncStatus`
 *
 * `:core:designsystem` has no dependency on `:core:model`, and this enum is why. The obvious
 * version of this component takes a `SyncStatus` and decides internally how to colour and
 * word it — which would mean the design system knew about beneficiary records, sync engines
 * and, worst of all, what to *call* a conflicted record in the user's language.
 *
 * Each of those is a decision belonging to the feature. So the feature maps
 * `SyncStatus -> (text, tone)` and this component renders whatever it is handed. The design
 * system stays usable by a second feature that has nothing to do with sync, and the
 * user-facing wording stays in the module that owns the string resources.
 *
 * The four tones are ordered by urgency rather than named for colours, for the same reason
 * the palette tokens are: [Critical] survives a decision that critical things are magenta.
 */
enum class StatusTone {
    Neutral,
    Info,
    Warning,
    Critical,
}
