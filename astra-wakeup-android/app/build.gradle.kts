plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.astra.wakeup"
    compileSdk = 34

    signingConfigs {
        create("release") {
            val ksPath = project.findProperty("ASTRA_KEYSTORE_PATH") as String?
            val keyAliasProp = project.findProperty("ASTRA_KEY_ALIAS") as String?
            val keyPassProp = project.findProperty("ASTRA_KEY_PASSWORD") as String?
            val storePassProp = project.findProperty("ASTRA_STORE_PASSWORD") as String?
            if (!ksPath.isNullOrBlank()) {
                storeFile = file(ksPath)
                keyAlias = keyAliasProp
                keyPassword = keyPassProp
                storePassword = storePassProp
            }
        }
    }

    defaultConfig {
        applicationId = "com.astra.wakeup"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val ksPath = project.findProperty("ASTRA_KEYSTORE_PATH") as String?
            if (!ksPath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
