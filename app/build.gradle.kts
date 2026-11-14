plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.colorosdebloater"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.colorosdebloater"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.8"
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
