plugins {
    id("quietfiles.android.library")
    id("quietfiles.hilt")
}

android {
    namespace = "io.quietfiles.core.common"
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.biometric)
}
