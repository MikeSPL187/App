plugins {
    id("quietfiles.android.application")
    id("quietfiles.android.compose")
    id("quietfiles.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.quietfiles"

    defaultConfig {
        applicationId = "io.quietfiles"
        versionCode = 3
        versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    androidResources {
        localeFilters += listOf("ru", "en")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Подпись релизным ключом настраивается в CI; локально подписываем debug-ключом, чтобы APK можно было поставить.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/*.version",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:storage"))
    implementation(project(":core:data"))
    implementation(project(":core:operations"))
    implementation(project(":core:network"))
    implementation(project(":core:security"))
    implementation(project(":feature:home"))
    implementation(project(":feature:browser"))
    implementation(project(":feature:search"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:viewer"))
    implementation(project(":feature:player"))
    implementation(project(":feature:cleaner"))
    implementation(project(":feature:network"))
    implementation(libs.coil.gif)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.compose.material3.windowsize)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.media3.test.utils.robolectric)
    testImplementation(libs.truth)
    testImplementation(libs.androidx.exifinterface)
    testImplementation(libs.hilt.android.testing)
    testImplementation(libs.sshd.core)
    testImplementation(libs.sshd.sftp)
    testImplementation(libs.commons.net)
    kspTest(libs.hilt.compiler)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.compose.ui.test.manifest)
}
