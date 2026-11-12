import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// FCM switches on when the Firebase config is there (docs/FIREBASE.md); without it the app builds and works without push.
val hasFirebase = file("google-services.json").exists()
if (hasFirebase) apply(plugin = libs.plugins.google.services.get().pluginId)

// Release signing: keystore.properties (outside git) points at the key; without it the release build is unsigned.
val signing = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "ru.ashcrm.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "ru.ashcrm.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 10
        versionName = "0.6.1"
        // The contract of the app with the CRM (docs/ANDROID_CONTRACT.md): another major version — the update is a must.
        buildConfigField("String", "CONTRACT", "\"1.5\"")
        // Versions and the APK come from GitVerse (README → «Выпуск версии»). Its API is closed even for reading a public
        // repository: release/android.json is read as a file of the branch main; it points at the APK of a release.
        buildConfigField("String", "UPDATE_MANIFEST", "\"https://gitverse.ru/api/repos/AshByte/crm-ashbyte-android/raw/branch/main/release/android.json\"")
        buildConfigField("String", "RELEASES_PREFIX", "\"https://gitverse.ru/api/attachments/\"")
        buildConfigField("boolean", "HAS_FIREBASE", hasFirebase.toString())
    }

    signingConfigs {
        if (signing.isNotEmpty()) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
                // v3: lets the key be rotated one day without reinstalling the app on phones.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions.unitTests.isReturnDefaultValues = true
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    // The QR scanner (§3.4): CameraX + ZXing — no Google Play Services, phones without them exist.
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    testImplementation(libs.junit)
}
