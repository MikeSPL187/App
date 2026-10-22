plugins {
    id("quietfiles.android.library")
    id("quietfiles.hilt")
    alias(libs.plugins.room)
}

android {
    namespace = "io.quietfiles.core.data"
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:storage"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.robolectric)
}
