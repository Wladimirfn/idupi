import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// The helper must carry the same signature as com.idupi.app: the main app
// reaches it through a signature-protected Binder. Same secrets source as
// :app (keystore.properties at the repo root or environment variables); a
// relative storeFile resolves against app/, where the keystore lives.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.idupi.shell"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.idupi.shell"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "0.4"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("app").resolve(
                signingValue("storeFile", "IDUPI_KEYSTORE_FILE") ?: "idupi-release.keystore"
            )
            storePassword = signingValue("storePassword", "IDUPI_KEYSTORE_PASSWORD")
            keyAlias = signingValue("keyAlias", "IDUPI_KEY_ALIAS") ?: "idupi"
            keyPassword = signingValue("keyPassword", "IDUPI_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    testImplementation(libs.junit)
}
