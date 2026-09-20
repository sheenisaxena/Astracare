package com.astracare.core.data

import com.astracare.core.common.log.Logger
import com.astracare.core.common.time.TimeProvider
import com.astracare.core.data.database.dao.DraftDao
import com.astracare.core.data.database.entity.DraftEntity
import com.astracare.core.data.remote.MockRemoteBeneficiarySource
import com.astracare.core.data.repository.RoomDraftRepository
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.CaptureDraft
import com.astracare.core.model.Measurement
import com.astracare.core.model.SyncStatus
import com.astracare.core.model.Timestamp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * No personally identifying information reaches the log.
 *
 * ## Why this is a test and not a code review
 *
 * DECISION_LOG 10.10 audited every logging call site by hand on Day 16 and found nothing
 * leaking. That was worth doing and it expires the moment someone adds a line — an audit is a
 * statement about a commit, not about the code.
 *
 * Day 19's `Logger` seam is what makes the check mechanical. Every diagnostic in the app now
 * goes through one interface, so a test can substitute a recorder, drive the real code paths
 * with a beneficiary whose name and village are unmistakable strings, and assert that neither
 * ever appears. That is the difference between "we looked" and "it cannot".
 *
 * ## The cause chain is inspected too, and that is the interesting part
 *
 * `Logger.warn` takes a `Throwable`, and a throwable carries a message written by whoever threw
 * it. SQLite errors normally contain the failing *statement* and not the bound values — which
 * is why the hand audit passed — but that is a property of the current driver, not a guarantee,
 * and nothing in this app controls it.
 *
 * So [RecordingLogger] flattens the whole cause chain into what it captures, and the draft
 * tests below throw exceptions whose messages look like real SQLite errors. If a future driver,
 * or a future wrapper, ever starts embedding values in an exception message, this test fails.
 * The residual risk is named in DECISION_LOG 14.4 rather than assumed away.
 *
 * ## What it cannot cover
 *
 * `SyncBeneficiariesWorker` logs too and is not exercised here — it cannot be constructed
 * without WorkManager. Its messages are `PushSummary` and `PullSummary`, which carry counts
 * and no records. Stated rather than silently omitted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PiiLoggingTest {

    private val dispatcher = StandardTestDispatcher()
    private val logger = RecordingLogger()

    @Test
    fun `a full push and pull cycle logs no name or village`() = runTest(dispatcher) {
        val remote = MockRemoteBeneficiarySource(TimeProvider { Timestamp(NOW) }, logger, dispatcher)

        // Every logging branch the mock server has: a plain accept, an idempotent re-accept, a
        // rejection, the deterministic transient failure, and a pull that edits server-side.
        remote.push(beneficiary("1"))
        remote.push(beneficiary("1"))
        remote.push(beneficiary("2", village = ""))
        repeat(TRANSIENT_FAILURE_EVERY) { remote.push(beneficiary("burn-$it")) }
        repeat(SERVER_EDIT_EVERY) { remote.pullChangedSince(null) }

        logger.assertNothingIdentifying()
    }

    @Test
    fun `draft failures log no name or village, even through the exception`() = runTest(dispatcher) {
        val repository = RoomDraftRepository(ThrowingDraftDao, logger, TimeProvider { Timestamp(NOW) }, dispatcher)

        // All three swallow-and-log paths. The draft is the worst case for a leak: it holds the
        // same PII as a record and it fails while a health worker is mid-form.
        repository.load()
        repository.save(draft())
        repository.clear()

        assertTrue("no failure was logged, so this test proved nothing", logger.captured.isNotEmpty())
        logger.assertNothingIdentifying()
    }

    @Test
    fun `the recorder would catch a leak, including one arriving through a cause`() {
        // The control. Without it, `assertNothingIdentifying` passing could mean the search is
        // wrong, the recorder is empty, or the app is clean — and only the third is good news.
        val leaky = RecordingLogger()

        leaky.debug("T", "saving $NAME")
        leaky.warn("T", "write failed", IOException("could not insert village=$VILLAGE"))

        assertTrue(leaky.captured.any { it.contains(NAME) })
        assertTrue("a cause message was not searched", leaky.captured.any { it.contains(VILLAGE) })
    }

    private fun beneficiary(id: String, village: String = VILLAGE) = Beneficiary(
        id = BeneficiaryId(id),
        name = NAME,
        ageYears = 3,
        village = village,
        measurement = Measurement(weightKg = 12.4, heightCm = 91.0, muacMm = null),
        recordedAt = Timestamp(0L),
        updatedAt = Timestamp(1_000L),
        syncStatus = SyncStatus.PENDING,
    )

    private fun draft() = CaptureDraft(
        name = NAME,
        ageYears = "3",
        village = VILLAGE,
        weightKg = "12.4",
        heightCm = "91.0",
        muacMm = "",
    )

    private companion object {
        const val NOW = 1_700_000_000_000L

        /**
         * Deliberately not "Asha Devi" and "Kotri", which every other test uses.
         *
         * A substring search for a plausible name risks matching something incidental — a
         * package name, a status, a word in a message. These match nothing but themselves, so
         * a hit is a leak and never a coincidence.
         */
        const val NAME = "Zzyzx Qwertyuiop"
        const val VILLAGE = "Xylophonia"

        /** Mirrors the private constants in the mock, so every logging branch is reached. */
        const val TRANSIENT_FAILURE_EVERY = 5
        const val SERVER_EDIT_EVERY = 3
    }
}

/**
 * Captures everything written, flattened.
 *
 * Tag, message and the full cause chain go into one string per call, because a leak does not
 * care which parameter it travelled in. Flattening at capture time rather than at assertion
 * time means a test that checks the wrong field cannot be written.
 */
private class RecordingLogger : Logger {

    val captured = mutableListOf<String>()

    override fun debug(tag: String, message: String) = record(tag, message, null)

    override fun info(tag: String, message: String, cause: Throwable?) = record(tag, message, cause)

    override fun warn(tag: String, message: String, cause: Throwable?) = record(tag, message, cause)

    fun assertNothingIdentifying() {
        val leaked = captured.filter { line ->
            line.contains("Zzyzx Qwertyuiop") || line.contains("Xylophonia")
        }
        check(leaked.isEmpty()) {
            "personally identifying information reached the log:\n" + leaked.joinToString("\n")
        }
    }

    private fun record(tag: String, message: String, cause: Throwable?) {
        captured += buildString {
            append(tag).append(' ').append(message)
            generateSequence(cause, Throwable::cause).forEach { append(" | ").append(it.message) }
        }
    }
}

/**
 * A DAO that fails the way SQLite does.
 *
 * The messages imitate real driver output: they name the statement and not the bound values,
 * which is exactly why the Day 16 hand audit found nothing. They are here so that if that ever
 * stops being true — a driver change, a wrapper that adds context — the test above notices.
 */
private object ThrowingDraftDao : DraftDao {

    override suspend fun load(id: Int): DraftEntity =
        error("error while compiling: SELECT * FROM capture_draft WHERE id = ?")

    override suspend fun upsert(entity: DraftEntity): Unit =
        error("database or disk is full (code 13 SQLITE_FULL)")

    override suspend fun clear(id: Int): Unit =
        error("attempt to write a readonly database (code 1032)")
}
