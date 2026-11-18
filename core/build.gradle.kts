import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// What the app does with the control node, without Android: it is tested on any JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.kage)
    // the one kage brings: Argon2 for the password
    implementation(libs.bouncycastle.prov)
    implementation(libs.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.bouncycastle.pkix)
}
