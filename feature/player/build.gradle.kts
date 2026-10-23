plugins {
    id("quietfiles.android.feature")
}

android {
    namespace = "io.quietfiles.feature.player"
}

dependencies {
    implementation(project(":core:storage"))
    implementation(project(":core:data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.service)
    api(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui.compose)
    implementation(libs.coil.compose)
    testImplementation(libs.robolectric)
}
