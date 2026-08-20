plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "one.globalconnect.keyinjection"
    compileSdk = 36

    defaultConfig {
        applicationId = "one.globalconnect.keyinjection"
        minSdk = 25
        targetSdk = 36
        versionCode = 14
        versionName = "1.14"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        @Suppress("UnstableApiUsage")
        androidResources {
            localeFilters += setOf("en", "es")
        }
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "DISABLE_PASSWORDS", "false")
            buildConfigField("boolean", "REQUEST_PASSWORD_CHANGE_ON_FIRST_USE", "false")
            buildConfigField(
                "String",
                "DEFAULT_PASS1_HASH",
                "\"1e1dba418e39a905a327ba58e47398ccb3a9b67660857e4b07f0d7d132923355\""
            )
            buildConfigField(
                "String",
                "DEFAULT_PASS2_HASH",
                "\"c35c448319ad8a62ac6296b5a2a000da27ca427b1451399cd538c7cf68a44b7e\""
            )

        }
        debug {
            buildConfigField("boolean", "DISABLE_PASSWORDS", "true")
            buildConfigField("boolean", "REQUEST_PASSWORD_CHANGE_ON_FIRST_USE", "false")
            buildConfigField(
                "String",
                "DEFAULT_PASS1_HASH",
                "\"1e1dba418e39a905a327ba58e47398ccb3a9b67660857e4b07f0d7d132923355\""
            )
            buildConfigField(
                "String",
                "DEFAULT_PASS2_HASH",
                "\"c35c448319ad8a62ac6296b5a2a000da27ca427b1451399cd538c7cf68a44b7e\""
            )

        }
    }
    applicationVariants.all {
        val appName = "GlobalConnect-Nexgo-KeyInjection"
        val variantVersionName = versionName
        val variantBuildType = buildType.name
        val signedSuffix = if (signingConfig != null) "signed" else "unsigned"
        outputs.all {
            val out = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            out.outputFileName = "$appName-$variantVersionName-$variantBuildType-$signedSuffix.apk"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("main") {
            // Load native libraries from the standard jniLibs directory
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
    packaging {
        jniLibs {
            // Required so bundled .so files are packaged under the correct ABI folders
            useLegacyPackaging = true
        }
    }
}

dependencies {
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.ui.test.manifest)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(fileTree("libs") { include("*.aar", "*.jar") })
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.junit)
}
