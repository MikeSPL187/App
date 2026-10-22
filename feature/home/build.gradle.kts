plugins {
    id("quietfiles.android.feature")
}

android {
    namespace = "io.quietfiles.feature.home"
}

dependencies {
    implementation(project(":core:storage"))
    implementation(project(":core:data"))
    implementation(project(":core:operations"))
    implementation(project(":feature:browser"))
}
