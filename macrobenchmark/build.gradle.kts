plugins {
    id("astracare.android.test")
}

android {
    namespace = "com.astracare.macrobenchmark"

    // The APK this instrumentation installs alongside and drives. Without it the module builds
    // and the benchmark fails at run time complaining it cannot find the target package —
    // which reads like a device problem rather than a missing line in a build file.
    targetProjectPath = ":app"

    buildTypes {
        // A macrobenchmark can only run against a build type that exists in BOTH modules, and
        // `debug` is disqualified: a debuggable process runs interpreted, disables JIT
        // optimisations and adds instrumentation overhead, so its startup time is a number
        // about the debugger. `release` is disqualified too — unsigned, and not installable.
        //
        // Hence a third build type in both modules. `matchingFallbacks` in :app is what tells
        // AGP which of the library modules' variants to use for it.
        create("benchmark") {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        // The two inherited types cannot produce a valid benchmark, and leaving them enabled
        // means a run started from the IDE's default variant silently measures nothing useful.
        // They stay, because AGP requires debug to exist; the benchmark simply refuses to run
        // on them (see StartupBenchmark's assumption check).
    }

    // Puts the instrumentation and the app under test in the same APK-install pass and lets
    // the benchmark control compilation of the target. Without it, CompilationMode has no
    // effect and every measurement silently becomes "whatever the device had already
    // compiled" — the failure mode where the harness reports numbers that mean nothing.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
}

// Benchmarks are not part of `check`. They need a physical device, take minutes, and produce
// a measurement rather than a pass/fail — wiring them into the build would make every CI run
// red on a machine that has no phone attached.
