package com.astracare.core.common.log

/**
 * Somewhere to write a diagnostic that is not `android.util.Log`.
 *
 * ## Why this exists, and why it took until Day 19
 *
 * `RoomDraftRepository` has called `android.util.Log` directly since Day 11 with a comment
 * admitting it was a shortcut, and DECISION_LOG 6.4 has carried it as an open item ever since,
 * with the trigger written as "the next diagnostic `adb logcat` cannot give". That is not what
 * forced it.
 *
 * What forced it was testability. `android.util.Log` is a stub in the JVM unit-test classpath
 * and every method on it throws — so **any class that calls it cannot have a plain unit test**.
 * `MockRemoteBeneficiarySource` is the whole sync engine's stand-in for a server, it had no
 * direct test of its own, and two `Log.d` calls were the only thing preventing one. A logging
 * call is not supposed to decide whether a class is testable.
 *
 * The escape hatches were both worse. Robolectric is a large dependency and a much slower test
 * to satisfy two log lines. `testOptions.unitTests.isReturnDefaultValues = true` is one line
 * and silently stubs every Android method in the module to return a default, so the *next*
 * test to touch a real framework API gets a quiet zero instead of an error.
 *
 * ## Deliberately small
 *
 * Three methods, `String` tags, no levels beyond what the app already uses, no structured
 * fields, no lazy message lambdas. This is a seam, not a logging framework — its job is to let
 * production code say something and let a test not care. Anything more would be designing for
 * a crash-reporting integration that does not exist, and the shape of that integration is not
 * knowable yet.
 *
 * The tag is a parameter rather than construction state, so adopting this was a mechanical
 * change at each call site rather than a redesign of how those classes are built.
 *
 * ## PII
 *
 * Nothing passed here may contain a beneficiary's name or village — see
 * `Beneficiary.PII_FIELDS` and DECISION_LOG 10.10, which audited every existing call site and
 * found them clean. This interface is now the single place that rule could be enforced
 * mechanically, which is a genuine reason it is better than the scattered `Log` calls it
 * replaced, and it is not enforced yet.
 */
interface Logger {

    /** Development detail. Expected to be compiled out or dropped in release builds. */
    fun debug(tag: String, message: String)

    /** Something worth knowing about that is not a problem. */
    fun info(tag: String, message: String, cause: Throwable? = null)

    /** Something went wrong and the app carried on. */
    fun warn(tag: String, message: String, cause: Throwable? = null)
}

/**
 * Writes nothing.
 *
 * The default for tests, so a unit test that does not care about logging says nothing about
 * it. Named and public rather than a private object in each test file, because "the fake for
 * this interface" should have exactly one definition.
 *
 * It is not the production default. There is no binding for [Logger] in any `:core` module —
 * `:app` supplies the Android-backed one — so forgetting to provide an implementation is a
 * compile-time Hilt error rather than an app that silently logs nothing.
 */
object NoOpLogger : Logger {
    override fun debug(tag: String, message: String) = Unit
    override fun info(tag: String, message: String, cause: Throwable?) = Unit
    override fun warn(tag: String, message: String, cause: Throwable?) = Unit
}
