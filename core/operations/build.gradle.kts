plugins {
    id("quietfiles.android.library")
    id("quietfiles.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.quietfiles.core.operations"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:storage"))
    implementation(project(":core:data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.junrar)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.robolectric)
}
