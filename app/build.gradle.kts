plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.debloater"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.debloater"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.9.0"
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
