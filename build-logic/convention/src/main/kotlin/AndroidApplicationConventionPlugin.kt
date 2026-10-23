import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.android")
        extensions.configure<ApplicationExtension> {
            configureKotlinAndroid(this)
            defaultConfig.targetSdk = Sdk.TARGET
            testOptions.unitTests.isIncludeAndroidResources = true
            // Тесты lint не проверяет: анализатор Kotlin в lint падает на тестах Compose UI, а правила lint про код приложения.
            lint.ignoreTestSources = true
        }
    }
}
