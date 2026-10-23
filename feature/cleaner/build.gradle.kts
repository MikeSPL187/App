plugins {
    id("quietfiles.android.feature")
}

android {
    namespace = "io.quietfiles.feature.cleaner"
}

dependencies {
    implementation(project(":core:storage"))
    implementation(project(":core:data"))
    implementation(project(":core:operations"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.robolectric)
}
