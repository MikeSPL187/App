plugins {
    id("quietfiles.android.feature")
}

android {
    namespace = "io.quietfiles.feature.settings"
}

dependencies {
    implementation(project(":core:storage"))
    implementation(project(":core:data"))
    implementation(project(":core:operations"))
}
