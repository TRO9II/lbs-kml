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
        versionCode = 60
        versionName = "3.24"
    }

    // Постоянная подпись: ключ кладётся в app/rubezh.jks из секрета GitHub SIGN_KEYSTORE_B64.
    // С одной и той же подписью новые версии ставятся поверх старой, без удаления.
    signingConfigs {
        create("rubezh") {
            storeFile = file("rubezh.jks")
            storePassword = "rubezh-sign"
            keyAlias = "rubezh"
            keyPassword = "rubezh-sign"
        }
    }

    buildTypes {
        debug {
            if (file("rubezh.jks").exists()) signingConfig = signingConfigs.getByName("rubezh")
        }
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
