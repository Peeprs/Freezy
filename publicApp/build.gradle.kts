import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) load(FileInputStream(file))
}

android {
    namespace = "com.freezy.publicapp"
    compileSdk = 34
    ndkVersion = "27.0.12077973"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        // La variante release reemplaza la app publicada actual.
        applicationId = "com.system.network.ui"
        minSdk = 28
        targetSdk = 36
        versionCode = 12
        versionName = "4.1.0"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    signingConfigs {
        if (keystoreProperties.containsKey("STORE_FILE")) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["STORE_FILE"] as String)
                storePassword = keystoreProperties["STORE_PASS"] as String
                keyAlias = keystoreProperties["KEY_ALIAS"] as String
                keyPassword = keystoreProperties["STORE_PASS"] as String
            }
        }
    }

    buildTypes {
        debug {
            // La copia pública Debug se instala junto a la app release.
            applicationIdSuffix = ".Debug"
            versionNameSuffix = "-Debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
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
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
}
