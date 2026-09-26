plugins {
    // No kotlin-android: AGP 9 compiles Kotlin itself. KSP 2.3+ runs on that built-in support.
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.launcher.home"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // The home grid, the adaptive-icon rasteriser and the widget host all assume 26+.
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        // 17, not 11: org.fossify:commons ships Java 17 bytecode.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // org.fossify:commons ships built against a newer Kotlin than most hosts pin.
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// Fossify Commons pulls a transitive dependency on the long-dead com.android.support
// artifacts, which collide with AndroidX. AGP 9 removed Jetifier, so this is the fix.
configurations.configureEach {
    exclude(group = "com.android.support")
}

dependencies {
    api(libs.fossify.commons)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // MyAppWidgetResizeFrame snapshots the occupied cells while a widget is being dragged.
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.coroutines.android)

    // Icon loading for the drawer and the widget previews.
    implementation(libs.glide)
    implementation(libs.timber)

    testImplementation(libs.junit)
    // Real org.json on the JVM test classpath; android.jar's stub returns null from every call.
    testImplementation(libs.org.json)
}
