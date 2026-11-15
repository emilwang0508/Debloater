plugins {
    id("com.android.application")
}

android {
    namespace = "com.naryo.debloater"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.naryo.debloater"
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "0.9.1-preview.3"
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
