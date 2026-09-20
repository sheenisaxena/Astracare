package com.astracare.log

import android.util.Log
import com.astracare.core.common.log.Logger
import javax.inject.Inject

/**
 * The production [Logger], writing to logcat.
 *
 * Lives in `:app` rather than in a core module, and that placement is the point of the seam.
 * `:core:common` is a Kotlin/JVM module and cannot reference `android.util.Log` at all — the
 * compiler enforces it — so the interface goes there and the only file in the project that
 * knows logcat exists is this one. Every other class takes a [Logger] and is unit-testable
 * without a device.
 *
 * `:app` is also where the rest of the platform wiring already lives: the WorkManager
 * configuration, the Hilt worker factory, the activity. A logging implementation is the same
 * kind of thing — an assembly detail of the application, not a capability of the domain.
 */
class AndroidLogger @Inject constructor() : Logger {

    override fun debug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun info(tag: String, message: String, cause: Throwable?) {
        Log.i(tag, message, cause)
    }

    override fun warn(tag: String, message: String, cause: Throwable?) {
        Log.w(tag, message, cause)
    }
}
