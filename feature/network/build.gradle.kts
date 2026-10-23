plugins {
    id("quietfiles.android.feature")
}

android {
    namespace = "io.quietfiles.feature.network"
}

dependencies {
    implementation(project(":core:storage"))
    implementation(project(":core:network"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.zxing.core)
    testImplementation(libs.robolectric)
}
