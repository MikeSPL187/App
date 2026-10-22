plugins {
    id("quietfiles.android.library")
    id("quietfiles.android.compose")
}

android {
    namespace = "io.quietfiles.core.designsystem"
}

dependencies {
    implementation(project(":core:model"))
    api(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    api(libs.coil.compose)
    api(libs.coil.video)
}
