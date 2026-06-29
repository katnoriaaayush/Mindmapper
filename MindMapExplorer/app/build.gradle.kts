plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mindmapper.explorer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mindmapper.explorer"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Android emulator reaches the host machine's localhost via 10.0.2.2.
        // For a physical device on the same LAN, replace with the host's LAN IP.
        // The ?display=board flag switches the web UI into the large-format touch skin.
        buildConfigField("String", "APP_URL", "\"http://10.0.2.2:3000/?display=board\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.webkit:webkit:1.11.0")
}
