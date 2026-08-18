import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.googleservices)
    alias(libs.plugins.firebase.crashlytics)
}

val lhProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val lhApiKey: String = lhProps.getProperty("lighthouse.apiKey", "")
val lhBaseUrl: String = lhProps.getProperty("lighthouse.baseUrl", "")

fun xorByteArrayLiteral(value: String, key: Int = 0x5A): String {
    if (value.isEmpty()) return "new byte[]{}"
    val parts = value.toByteArray(Charsets.UTF_8)
        .map { (it.toInt() xor key) and 0xFF }
        .map { if (it >= 0x80) it - 0x100 else it }   // Java byte is signed
        .joinToString(",")
    return "new byte[]{$parts}"
}

android {
    namespace = "com.calleridapp.numberlookup"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.calleridapp.numberlookup"
        // Raised from 24: org.fossify:commons (the launcher's UI/theming base) declares
        // minSdkVersion 26, and the launcher itself leans on API 25/26 LauncherApps
        // shortcut + pinned-item APIs.
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true

        // LightHouse credentials → obfuscated BuildConfig byte[] (decoded at runtime
        // by Obfuscated.s). buildConfig = true is enabled below.
        buildConfigField("byte[]", "LH_API_KEY", xorByteArrayLiteral(lhApiKey))
        buildConfigField("byte[]", "LH_BASE_URL", xorByteArrayLiteral(lhBaseUrl))
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Resource shrinking left off — enable only after verifying a release build.
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            // Resource shrinking left off — enable only after verifying a release build.
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        // 17, not 11: org.fossify:commons is compiled against Java 17.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        dataBinding = true
        viewBinding = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

base {
    val appName = "CallerIdNumberLookupBlock"
    val formattedDate: String =
        SimpleDateFormat("MMM.dd.yyyy", Locale.getDefault()).format(Date())
    val config = android.defaultConfig
    archivesName.set(
        "${appName}_${config.applicationId}_v${config.versionName}(${config.versionCode})_$formattedDate"
    )
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    implementation(libs.glide)
    implementation(libs.intuit.sdp)
    implementation(libs.intuit.ssp)
    implementation(libs.lottie)

    annotationProcessor(libs.glide.compiler)
    implementation(libs.shimmer)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Chucker — on-device HTTP(S) inspector. Real library in debug; no-op stub in
    // release so it ships nothing (no UI, no capture, zero overhead) to users.
    debugImplementation(libs.chucker)
    releaseImplementation(libs.chucker.noop)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.gson)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    implementation(libs.gms.play.services.ads)
    implementation(libs.firebase.config)
    implementation(libs.installreferrer)
    implementation(libs.firebase.analytics)
    implementation(libs.google.firebase.crashlytics)
    implementation(libs.facebook.android.sdk)
    implementation(libs.audience.network.sdk)
    implementation(libs.dexter)
    implementation(libs.app.update)
    implementation(libs.app.update.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.multidex)
    implementation(libs.libphonenumber)
    implementation(libs.libphonenumber.geocoder)
    implementation(libs.libphonenumber.carrier)

    // LightHouse push SDK (replaces OneSignal).
    implementation(libs.lighthouse)
    implementation(libs.lighthouse.extended)

    // ── Home-screen launcher ──────────────────────────────────────────────────
    // Fossify commons supplies the launcher's base activities, theming engine and
    // the view widgets its layouts reference.
    implementation(libs.fossify.commons) {
        // patternLockView (commons' app-lock screen) still depends on the pre-AndroidX
        // support library, which collides class-for-class with androidx.core / androidx.media.
        exclude(group = "com.android.support")
    }
    // commons keeps this one `implementation`, so the launcher's grid code has to ask
    // for it directly.
    implementation(libs.kotlinx.collections.immutable)
    // The launcher's own storage (app-drawer cache, home-screen grid, hidden icons) is
    // hand-rolled SQLite rather than Room: AGP 9's built-in Kotlin rejects KSP, and the
    // external Kotlin plugin needed for KSP does not support AGP 9.
}
