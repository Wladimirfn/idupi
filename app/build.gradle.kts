import java.io.File
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing secrets never live in this file. They come from
// keystore.properties at the repo root (git-ignored; see
// keystore.properties.example) or, for CI, from environment variables.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.idupi.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.idupi.app"
        minSdk = 29
        targetSdk = 36
        // Owner-visible update marker: every shipped build bumps both, so
        // "which APK am I running" is answerable from the system settings.
        // Same-versionCode installs get silently skipped by some phone
        // installers -- that is how a stale build masqueraded as the new one.
        versionCode = 66
        versionName = "1.65-acceso-avanzado"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file(signingValue("storeFile", "IDUPI_KEYSTORE_FILE") ?: "idupi-release.keystore")
            storePassword = signingValue("storePassword", "IDUPI_KEYSTORE_PASSWORD")
            keyAlias = signingValue("keyAlias", "IDUPI_KEY_ALIAS") ?: "idupi"
            keyPassword = signingValue("keyPassword", "IDUPI_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME is the UI's "which build am I running"
        // marker (Settings + nav drawer); AGP stops generating the class by
        // default unless this flag is on.
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests {
            // android.util.Log isn't mocked on the plain JVM test classpath (no Robolectric);
            // this makes calls like Log.w(...) return defaults instead of throwing.
            isReturnDefaultValues = true
        }
    }
}

// Phone shell (phone-shell T3b): the helper APK shipped inside IDUPI's assets.
// The helper is release-signed; a debug IDUPI build does not share that
// signature. That is fine here: adb (shell) installs the helper, and the
// signature-protected Binder that will care about the match arrives in T4.
val copyShellHelperApk = tasks.register<CopyShellHelperApkTask>("copyShellHelperApk") {
    dependsOn(":shellhelper:assembleRelease")
    helperApk.set(
        project(":shellhelper").layout.buildDirectory.file(
            "outputs/apk/release/shellhelper-release.apk"
        )
    )
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            copyShellHelperApk,
            CopyShellHelperApkTask::outputDir
        )
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    
    // Jetpack Compose
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui:1.6.1")
    implementation("androidx.compose.ui:ui-graphics:1.6.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.1")
    implementation("androidx.compose.material3:material3:1.2.0")
    implementation("androidx.compose.material:material-icons-extended:1.6.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Ktor Client & WebSockets
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)

    // DataStore-backed settings persistence (PR 3 / Task 4.2).
    implementation(libs.androidx.datastore.preferences)

    // Health Connect (phone-health T2): the phone reads steps, heart rate,
    // sleep and friends from the platform store. Pinned to the last stable
    // release; alpha/beta builds are not a base for a health data reader.
    implementation(libs.androidx.health.connect.client)

    // In-app adb client (phone-shell T2): pairing + `shell:` over the phone's
    // own Wireless debugging. Dual licensed GPL-3.0-or-later OR Apache-2.0;
    // used here under Apache-2.0. Conscrypt provides the TLS 1.3 stack the
    // pairing protocol needs; bcpkix builds our self-signed adb client cert.
    implementation(libs.libadb.android)
    implementation(libs.conscrypt.android)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.bouncycastle.bcprov)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

/**
 * Copies the helper's release APK into a generated assets directory. AGP wires
 * this directory into the variant's asset sources; the asset name is what
 * HelperApk reads at runtime.
 */
abstract class CopyShellHelperApkTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val helperApk: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copyApk() {
        val output = outputDir.get().asFile
        output.mkdirs()
        helperApk.get().asFile.copyTo(
            File(output, "idupi-shell-helper.apk"),
            overwrite = true
        )
    }
}
