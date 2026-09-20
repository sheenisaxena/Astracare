package com.astracare.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.astracare.core.data.crypto.DatabasePassphrase
import com.astracare.core.data.database.AstraCareDatabase
import com.astracare.core.data.database.entity.BeneficiaryEntity
import com.astracare.core.data.database.entity.MeasurementEmbedded
import com.astracare.core.data.database.migration.ALL_MIGRATIONS
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves the database file is actually encrypted, by reading it.
 *
 * ## Why this test exists in this form
 *
 * Every other way of "testing" encryption is a test of the configuration rather than of the
 * result: asserting that `openHelperFactory` was called, that a passphrase was non-empty, that
 * the SQLCipher class is on the classpath. All of those pass on a build where the factory is
 * wired up and silently not used — which is exactly the bug worth catching, because a database
 * that is quietly plaintext looks identical from inside the app.
 *
 * So this writes a real record through the real stack, closes the database, and then reads the
 * bytes off the disk with no SQLite involved at all. The claim being made is "a child's name is
 * not sitting in a file on this handset", and that claim is checkable directly.
 *
 * ## The negative control is half the test
 *
 * [an unencrypted database does leak the same name] writes the identical record to an
 * identical schema with no SQLCipher, and asserts the name **is** found. Without it, the
 * encryption assertion would also pass if `toEntity` silently dropped the field, if the record
 * never got written, or if the search were looking for the wrong encoding. A test that can only
 * pass is not evidence. This one fails if either half stops being true.
 *
 * Instrumented, not a unit test — the Android Keystore has no JVM implementation, so the
 * passphrase provider cannot run under Robolectric either. That puts this outside CI
 * (DECISION_LOG 4.5), which is recorded as an open item rather than pretended away.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private lateinit var encryptedFile: File
    private lateinit var plaintextFile: File

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        encryptedFile = context.getDatabasePath(ENCRYPTED_DB)
        plaintextFile = context.getDatabasePath(PLAINTEXT_DB)
        deleteDatabases()
    }

    @After
    fun tearDown() {
        deleteDatabases()
    }

    @Test
    fun `a record written through the app stack is not readable in the database file`() {
        val database = Room.databaseBuilder(context, AstraCareDatabase::class.java, ENCRYPTED_DB)
            .openHelperFactory(SupportOpenHelperFactory(DatabasePassphrase(context).get()))
            .apply { ALL_MIGRATIONS.forEach { addMigrations(it) } }
            .build()

        runBlocking { database.beneficiaryDao().upsert(record()) }
        // Closing flushes the WAL into the main file. Without it the row can still be sitting
        // in -wal and the assertion below would pass for the wrong reason.
        database.close()

        val bytes = encryptedFile.readBytes()

        assertFalse("the beneficiary's name is readable on disk", bytes.contains(NAME))
        assertFalse("the village is readable on disk", bytes.contains(VILLAGE))
        // SQLCipher encrypts the header too, so the file does not even announce itself as a
        // database. A plaintext SQLite file always begins with this string.
        assertFalse("the file still has a plaintext SQLite header", bytes.contains(SQLITE_MAGIC))
    }

    @Test
    fun `an unencrypted database does leak the same name`() {
        // The control. If this ever stops failing to hide the name, the test above has stopped
        // proving anything and is passing for some other reason.
        val database = Room.databaseBuilder(context, AstraCareDatabase::class.java, PLAINTEXT_DB)
            .apply { ALL_MIGRATIONS.forEach { addMigrations(it) } }
            .build()

        runBlocking { database.beneficiaryDao().upsert(record()) }
        database.close()

        val bytes = plaintextFile.readBytes()

        assertTrue("the control database did not store the name at all", bytes.contains(NAME))
        assertTrue("the control database is not a plaintext SQLite file", bytes.contains(SQLITE_MAGIC))
    }

    /** Naive substring search over the raw bytes. The file is a few KB; this is fast enough. */
    private fun ByteArray.contains(text: String): Boolean {
        val needle = text.toByteArray(Charsets.UTF_8)
        if (needle.isEmpty() || needle.size > size) return false
        return (0..size - needle.size).any { start ->
            needle.indices.all { this[start + it] == needle[it] }
        }
    }

    private fun record() = BeneficiaryEntity(
        id = "encryption-probe",
        name = NAME,
        ageYears = 3,
        village = VILLAGE,
        measurement = MeasurementEmbedded(weightKg = 12.4, heightCm = 91.0, muacMm = null),
        recordedAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
        syncStatus = "PENDING",
    )

    /** Removes the main file and its -wal / -shm companions, which `delete()` leaves behind. */
    private fun deleteDatabases() {
        listOf(ENCRYPTED_DB, PLAINTEXT_DB).forEach { name ->
            listOf("", "-wal", "-shm").forEach { suffix ->
                context.getDatabasePath(name + suffix).delete()
            }
        }
    }

    private companion object {
        // Deliberately not the production database name: this test deletes what it opens.
        const val ENCRYPTED_DB = "encryption_probe.db"
        const val PLAINTEXT_DB = "encryption_control.db"

        const val NAME = "Asha Devi"
        const val VILLAGE = "Kotri"

        /** The 16-byte header every unencrypted SQLite file starts with. */
        const val SQLITE_MAGIC = "SQLite format 3"
    }
}
