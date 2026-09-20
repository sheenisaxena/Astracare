# Building

Everything needed to get the project compiling, and the one environment problem that reliably
costs an hour if it is not called out.

**Requirements:** JDK 21 · Gradle 9.5 (via the wrapper) · AGP 9.3.1 · `compileSdk` 37 ·
`minSdk` 24

---

## `JAVA_HOME` must be set at the OS level

Not only inside the IDE. This is the one setup step that fails in a confusing way.

Android Studio's Gradle JDK setting (Settings → Build Tools → Gradle → Gradle JDK) is internal
to Studio. A build started from the IDE therefore succeeds while the git hooks fail with:

```
JAVA_HOME is not set and no 'java' command could be found in your PATH
```

Hooks run `./gradlew` in a plain shell that never sees Studio's setting. `org.gradle.java.home`
does not cover it either: that selects the JDK for the Gradle *daemon*, and the wrapper needs a
JVM before it can read the file that contains it.

```bash
# macOS / Linux — in ~/.zshenv, not ~/.zshrc, so non-interactive shells inherit it
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"   # Linux: e.g. /usr/lib/jvm/temurin-21-jdk

"$JAVA_HOME/bin/java" -version                       # must report 21
```

```powershell
# Windows — user-level; restart Android Studio and any open terminals afterwards
[Environment]::SetEnvironmentVariable('JAVA_HOME','C:\Program Files\Eclipse Adoptium\jdk-21','User')
```

Android Studio's bundled runtime (`<studio>/jbr`) works as a fallback if its `java -version`
reports 21, but it moves on Studio updates — a standalone JDK is the more stable target.

**PowerShell:** every `./gradlew` below is `.\gradlew.bat`. PowerShell does not have the current
directory on its PATH, so the bare name is not found even though the file is right there.

---

## Commands

```bash
./gradlew assembleDebug
./gradlew test                        # unit tests + the Robolectric end-to-end UI test
./gradlew connectedDebugAndroidTest   # migrations, DAO queries, encryption — needs a device
./gradlew detekt                      # static analysis + ktlint rules, maxIssues = 0
./gradlew detekt --auto-correct       # fix what can be fixed automatically
```

Benchmarks are deliberately not part of `check`. See [PERFORMANCE.md](PERFORMANCE.md) for how to
run them and why they are excluded.

---

## Git hooks install themselves

detekt on commit, tests on push. No setup command.

Git never clones `.git/config`, so `core.hooksPath` cannot survive a clone on its own: the hook
files arrive and git ignores them, leaving a fresh machine with no checks at all.
`settings.gradle.kts` sets it during configuration instead, which means it is applied on the
first Gradle sync.

```bash
git config core.hooksPath   # should print .githooks
```

---

## First run on a device, after Day 16

The database is encrypted with SQLCipher from schema v4 onwards. An existing **plaintext**
`astracare.db` left over from an earlier development build will not open, and the app will fail
at startup rather than silently recreating it — deliberately, because silently discarding a
health worker's unsent records is the worse behaviour.

Uninstall any older build before installing a current one.

---

## Known build environment issues

| Symptom | Cause |
|---|---|
| `android.disallowKotlinSourceSets` required in `gradle.properties` | KSP registers generated sources via `kotlin.sourceSets`, which AGP 9 rejects. Third-party tooling gap; see DECISION_LOG 3.5. |
| `MigrationTest` fails on a missing schema file | `core/data/schemas/` holds only `1.json`; `2.json` and `3.json` were never committed and must be recovered from history. The recipe is in that test's KDoc. |
| `coldStartBaselineProfile` fails | Correct: `BaselineProfileMode.Require` refuses to report a number when no profile is installed. Generate and commit one — see [PERFORMANCE.md](PERFORMANCE.md). |
