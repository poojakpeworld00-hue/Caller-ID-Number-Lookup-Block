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
    namespace = "identifycaller.phonelookup.contacts.calllog"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "identifycaller.phonelookup.contacts.calllog"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        dataBinding = true
        viewBinding = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(11)
}

base {
    val appName = "CallerIdPhoneLookup"
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
}
