plugins {
    id("quietfiles.android.feature")
}

android {
    namespace = "io.quietfiles.feature.viewer"
}

dependencies {
    implementation(project(":core:storage"))
    implementation(project(":core:data"))
    implementation(project(":core:operations"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.coil.gif)
    testImplementation(libs.robolectric)
}
