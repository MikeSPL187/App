import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal object Sdk {
    const val COMPILE = 36
    const val TARGET = 36
    const val MIN = 30
}

internal fun Project.configureKotlinAndroid(extension: CommonExtension<*, *, *, *, *, *>) {
    extension.apply {
        compileSdk = Sdk.COMPILE
        defaultConfig {
            minSdk = Sdk.MIN
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        lint {
            abortOnError = true
            checkDependencies = false
            warningsAsErrors = false
            disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        }
    }
    configureKotlin()
}

internal fun Project.configureKotlin() {
    // Имена файлов в тестах содержат кириллицу; без UTF-8 локали JVM превращает их в «?».
    tasks.withType<Test>().configureEach {
        environment("LC_ALL", "C.UTF-8")
        environment("LANG", "C.UTF-8")
        systemProperty("file.encoding", "UTF-8")
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                "-Xconsistent-data-class-copy-visibility",
            )
        }
    }
}
