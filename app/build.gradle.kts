import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.ksp)
}

val localProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.isFile) file.inputStream().use { load(it) }
    }
val debugDefaultOrigin: String = localProperties.getProperty("defaultOrigin", "")

android {
    namespace = "io.github.szymonrychu.booxsketch"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.szymonrychu.booxsketch"
        minSdk = 30
        targetSdk = 35
        versionName = (project.findProperty("versionName") as String?) ?: "0.0.0"
        versionCode = ((project.findProperty("versionCode") as String?) ?: "1").toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEFAULT_ORIGIN", "\"${debugDefaultOrigin.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "DEFAULT_ORIGIN", "\"\"")
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }

    packaging {
        jniLibs.pickFirsts += "**/libc++_shared.so"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        abortOnError = true
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:api"))
    implementation(project(":ink:onyx"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.roborazzi)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.truth)
}

// onyxsdk-base pulls the pre-AndroidX support libraries (24.2.1), which duplicate AndroidX classes.
configurations.configureEach {
    exclude(group = "com.android.support")
}
