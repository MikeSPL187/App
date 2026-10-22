plugins {
    id("quietfiles.android.library")
    id("quietfiles.android.compose")
}

android {
    namespace = "io.quietfiles.core.designsystem"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.androidx.core.ktx)
    api(libs.coil.compose)
    implementation(libs.coil.video)
}
