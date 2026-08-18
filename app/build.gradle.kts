plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.notekeep.local"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.notekeep.local"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Signs release builds with the persistent release keystore when its 4 env vars are present
    // (set by GitHub Actions from repo secrets - see .github/workflows/build.yml), so every build
    // shares one stable certificate and installs as an update over the previous one instead of
    // needing an uninstall each time. Left undefined (release build falls back to no explicit
    // signingConfig) when those env vars aren't set, so a plain local `gradle assembleRelease`
    // still works without failing on a missing keystore file.
    val storeFilePath = System.getenv("NOTESLINK_KEYSTORE_PATH")
    val storePass = System.getenv("NOTESLINK_KEYSTORE_PASSWORD")
    val keyAliasEnv = System.getenv("NOTESLINK_KEY_ALIAS")
    val keyPass = System.getenv("NOTESLINK_KEY_PASSWORD")
    val hasReleaseSigning = !storeFilePath.isNullOrBlank() && !storePass.isNullOrBlank() &&
        !keyAliasEnv.isNullOrBlank() && !keyPass.isNullOrBlank()

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(storeFilePath!!)
                storePassword = storePass
                keyAlias = keyAliasEnv
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")
}
