plugins {
    id("astracare.android.library")
    // Applies KSP as well as Hilt, so KSP is not declared again here — Room's processor
    // registers against the same KSP instance.
    id("astracare.android.hilt")
}

android {
    namespace = "com.astracare.core.data"
}

ksp {
    // Writes the schema JSON to core/data/schemas on every build. These files are COMMITTED:
    // they are what a migration test asserts against. Without an exported v1 schema there is
    // nothing to migrate from in a test, and migrations end up verified by shipping them —
    // which on this app means losing field data that never reached the server.
    arg("room.schemaLocation", "$projectDir/schemas")
    // Generate Kotlin rather than Java for Room's implementation classes.
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    // SQLCipher replaces the framework SQLite implementation underneath Room, so it is a
    // dependency of this module alone — nothing above :core:data knows the database is
    // encrypted, which is the property that makes it removable.
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    // Lets a @Query return PagingSource. Without it Room's processor rejects the return type
    // with an error that reads like a Paging problem rather than a missing artifact.
    implementation(libs.room.paging)
    ksp(libs.room.compiler)

    // androidTest, not test: MigrationTestHelper and in-memory Room need instrumentation.
    androidTestImplementation(libs.room.testing)
}
