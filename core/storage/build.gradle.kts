plugins {
    id("quietfiles.android.library")
    id("quietfiles.hilt")
}

android {
    namespace = "io.quietfiles.core.storage"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    testImplementation(libs.robolectric)
}
