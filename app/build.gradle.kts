plugins {
    id("com.android.application")
}

android {
    namespace = "ru.lbs.kml"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.lbs.kml"
        minSdk = 29
        targetSdk = 35
        versionCode = 30
        versionName = "3.9.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("com.google.zxing:core:3.5.3")
}
