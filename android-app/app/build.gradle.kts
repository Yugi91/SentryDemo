import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.sentry)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val sentryDsn: String = localProps.getProperty("sentry.dsn", "")
val sentryEnv: String = localProps.getProperty("sentry.environment", "debug")
val sentryUserId: String = localProps.getProperty("sentry.userId", "demo-user-001")

android {
    namespace = "io.pula.sentrydemo"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.pula.sentrydemo"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")
        buildConfigField("String", "SENTRY_ENV", "\"$sentryEnv\"")
        buildConfigField("String", "DEMO_USER_ID", "\"$sentryUserId\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Sentry Gradle plugin: upload ProGuard mappings & source context to your self-hosted Sentry.
// Disabled by default for local demo — flip `uploadEnabled` and provide auth token in CI to enable.
sentry {
    autoUploadProguardMapping.set(false)
    includeProguardMapping.set(false)
    autoUploadNativeSymbols.set(false)
    tracingInstrumentation {
        enabled.set(true)
        features.set(setOf(
            io.sentry.android.gradle.extensions.InstrumentationFeature.DATABASE,
            io.sentry.android.gradle.extensions.InstrumentationFeature.FILE_IO,
            io.sentry.android.gradle.extensions.InstrumentationFeature.OKHTTP,
            io.sentry.android.gradle.extensions.InstrumentationFeature.COMPOSE,
        ))
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.sentry.android)
}
