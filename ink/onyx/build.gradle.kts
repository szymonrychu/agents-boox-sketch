plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.szymonrychu.booxsketch.onyx"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.pickFirsts += "**/libc++_shared.so"
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

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.onyx.pen)
    implementation(libs.onyx.device)
    implementation(libs.onyx.base)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
}

// onyxsdk-base pulls the pre-AndroidX support libraries (24.2.1), which duplicate AndroidX classes.
// com.tencent:mmkv stays: its runtime use by the Onyx pen/base SDK is unproven and losing it could silently kill pen input.
configurations.configureEach {
    exclude(group = "com.android.support")
}
