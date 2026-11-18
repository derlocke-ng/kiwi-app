plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.derlocke_ng.kiwi"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.derlocke_ng.kiwi"
        minSdk = 26
        targetSdk = 37
        // 0.1.<build>: the build is the number of commits, handed in by container/gradlew and
        // the release workflow, so every build of main is newer than the one before. Without
        // it (a plain ./gradlew) the app is build 1 and installs over nothing newer.
        val build = System.getenv("KIWI_BUILD")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "${providers.gradleProperty("kiwi.version").get()}.$build"
    }

    // The release key is not in the repository: the release workflow hands it in. Without it
    // a release build stays unsigned.
    val keystore = System.getenv("KIWI_KEYSTORE_FILE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("KIWI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KIWI_KEY_ALIAS")
                keyPassword = System.getenv("KIWI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Nothing from Google in the APK: F-Droid rejects the dependency metadata block.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.biometric)
    // biometric alone brings fragment 1.2.5, which crashes when a permission is asked for
    // through the activity result API ("Can only use lower 16 bits for requestCode")
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // the camera and a QR reader, for the invite: both free software, neither needs Play services
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.coroutines.android)
    implementation(project(":core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
