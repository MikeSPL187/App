plugins {
    id("quietfiles.android.library")
    id("quietfiles.hilt")
}

android {
    namespace = "io.quietfiles.core.security"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    testImplementation(libs.robolectric)
}
