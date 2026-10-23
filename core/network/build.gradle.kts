plugins {
    id("quietfiles.android.library")
    id("quietfiles.hilt")
}

android {
    namespace = "io.quietfiles.core.network"
    // Серверной стороне TLS в тестах (Conscrypt под Robolectric) нужен доступ к InetAddress.
    testOptions.unitTests.all { it.jvmArgs("--add-opens=java.base/java.net=ALL-UNNAMED") }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:storage"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:security"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.sshj)
    implementation(libs.smbj)
    implementation(libs.commons.net)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    // Все части BouncyCastle одной версии: smbj и sshj тянут разные, а смесь падает при загрузке провайдера.
    implementation(libs.bouncycastle.util)
    testImplementation(libs.robolectric)
    testImplementation(libs.sshd.core)
    testImplementation(libs.sshd.sftp)
    testImplementation(libs.ftpserver.core)
}
