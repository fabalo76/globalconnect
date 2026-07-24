@file:Suppress("UnstableApiUsage")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStoreFilePath = providers.gradleProperty("XTMS_RELEASE_STORE_FILE")
    .orElse(providers.environmentVariable("XTMS_RELEASE_STORE_FILE"))
    .orNull
val releaseStorePassword = providers.gradleProperty("XTMS_RELEASE_STORE_PASSWORD")
    .orElse(providers.environmentVariable("XTMS_RELEASE_STORE_PASSWORD"))
    .orNull
val releaseKeyAlias = providers.gradleProperty("XTMS_RELEASE_KEY_ALIAS")
    .orElse(providers.environmentVariable("XTMS_RELEASE_KEY_ALIAS"))
    .orNull
val releaseKeyPassword = providers.gradleProperty("XTMS_RELEASE_KEY_PASSWORD")
    .orElse(providers.environmentVariable("XTMS_RELEASE_KEY_PASSWORD"))
    .orNull
val productionSigningConfigured = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "one.globalconnect.xtmsagent"
    //noinspection GradleDependency
    compileSdk = 36

    buildFeatures.buildConfig = true

    flavorDimensions += "client"

    productFlavors {
        create("globalconnect") {
            dimension = "client"
            applicationIdSuffix = ".globalconnect"
            buildConfigField("String",  "DEFAULT_SEED_0",    "\"22687075\"")
            buildConfigField("String",  "DEFAULT_SEED_1",    "\"27071287\"")
            buildConfigField("boolean", "FORCE_PWD_CHANGE",  "false")
        }
        create("banpais") {
            dimension = "client"
            applicationIdSuffix = ".banpais"
            buildConfigField("String",  "DEFAULT_SEED_0",    "\"22687075\"")
            buildConfigField("String",  "DEFAULT_SEED_1",    "\"27071287\"")
            buildConfigField("boolean", "FORCE_PWD_CHANGE",  "false")
        }
        create("banrural") {
            dimension = "client"
            applicationIdSuffix = ".banrural"
            buildConfigField("String",  "DEFAULT_SEED_0",    "\"22687075\"")
            buildConfigField("String",  "DEFAULT_SEED_1",    "\"27071287\"")
            buildConfigField("boolean", "FORCE_PWD_CHANGE",  "false")
        }
    }

    defaultConfig {
        applicationId = "one.globalconnect.xtmsagent"
        minSdk = 29
        //noinspection OldTargetApi
        targetSdk = 35
        versionCode = 47
        versionName = "2.1.2.47"
        buildConfigField("String", "GLOBAL_CONNECT_ENV", "\"dev\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("debugKeystore") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (productionSigningConfigured) {
            create("production") {
                storeFile = file(requireNotNull(releaseStoreFilePath))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties",
            "META-INF/DEPENDENCIES",
        )
        jniLibs.keepDebugSymbols += setOf("**/*.so")
    }

    buildTypes {
        debug {
            isDebuggable = true
            // Temporarily disabled to avoid LLDB connection issues
            // isJniDebuggable = true
            buildConfigField("String", "VERSION", "\"${generateGitInfo()}\"")
            buildConfigField("String", "BUILD_TYPE", "\"debug\"")
        }

        release {
            buildConfigField("String", "VERSION", "\"${generateGitInfo()}\"")
            buildConfigField("String", "BUILD_TYPE", "\"release\"")
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("production")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_19
        targetCompatibility = JavaVersion.VERSION_19
    }
    kotlinOptions {
        jvmTarget = "19"
    }
    val signingLabels = mutableMapOf(
        signingConfigs.getByName("debugKeystore") to "GlobalConnectDebugKey",
    ).apply {
        signingConfigs.findByName("production")?.let { put(it, "GlobalConnectProductionKey") }
    }

    applicationVariants.all {
        this.outputs
            .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
            .forEach { output ->
                val signed = signingLabels[this.signingConfig] ?: this.signingConfig?.keyAlias ?: "unsigned"
                val flavor = this.flavorName.replaceFirstChar { it.uppercase() }
                output.outputFileName = "xTMSAgent-${flavor}-${versionName}-${generateGitInfo()}-${this.buildType.name}-${signed}.apk"
            }
    }
}

dependencies {

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.ui:ui-android:1.10.6")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.03.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.03.01"))
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.10.6")
    implementation("com.google.code.gson:gson:2.13.2")

    // MQTT — HiveMQ client (MQTT 3.1.1 / 5.0, async API, coroutine-friendly)
    implementation("com.hivemq:hivemq-mqtt-client:1.3.13")

    // Kotlin coroutines — used by TmsMqttManager reconnection loop
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // WorkManager — periodic status heartbeat
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // EncryptedSharedPreferences — secure storage for derived MQTT credentials
    implementation("androidx.security:security-crypto:1.1.0")

    // OkHttp — WebSocket client for Kinesis Video Streams WebRTC signaling
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // WebRTC — screen capture, peer connection, and data channel for Kinesis remote control
    implementation("io.getstream:stream-webrtc-android:1.3.10")

    implementation(files("./libs/nexgo-smartpos-sdk-v3.08.010_20250528.aar"))
}

fun generateGitInfo(): String {
    return providers.gradleProperty("buildStamp").orElse("nogit").get()
}
