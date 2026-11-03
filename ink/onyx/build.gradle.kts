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

    lint {
        abortOnError = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.onyx.pen)
    implementation(libs.onyx.device)
    implementation(libs.onyx.base)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

// onyxsdk-base pulls the pre-AndroidX support libraries (24.2.1), which duplicate AndroidX classes.
configurations.configureEach {
    exclude(group = "com.android.support")
}
