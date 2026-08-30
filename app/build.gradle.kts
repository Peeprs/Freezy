plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

import java.util.Properties
import java.io.FileInputStream

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) load(FileInputStream(file))
}

android {
    namespace = "com.system.network.ui"
    compileSdk = 34
    ndkVersion = "27.0.12077973"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.system.network.ui" // Nombre camuflado contra Anti-Cheats
        minSdk = 28 // Android 9 (Pie)
        targetSdk = 36
        versionCode = 11
        versionName = "4.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false // MobSF detecta debuggable=true como riesgo

            if (keystoreProperties.containsKey("STORE_FILE")) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = rootProject.file(keystoreProperties["STORE_FILE"] as String)
                    storePassword = keystoreProperties["STORE_PASS"] as String
                    keyAlias = keystoreProperties["KEY_ALIAS"] as String
                    keyPassword = keystoreProperties["STORE_PASS"] as String
                }
            }

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".Debug"
            versionNameSuffix = "-Debug"
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}