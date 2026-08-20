plugins {
    id("astracare.jvm.library")
}

// No dependencies by design. Timestamp is a value class over epoch millis rather than a
// date-time library type, so this module compiles against nothing but the Kotlin stdlib —
// which is what keeps a future multiplatform extraction feasible. See DECISION_LOG.
