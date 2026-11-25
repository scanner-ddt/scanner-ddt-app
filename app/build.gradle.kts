plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.scannerddt.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.scannerddt.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Firma fissa: permette di aggiornare l'app senza disinstallarla
    signingConfigs {
        create("ddt") {
            storeFile = file("ddt.keystore")
            storePassword = "scannerddt"
            keyAlias = "ddt"
            keyPassword = "scannerddt"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("ddt")
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Scanner di documenti di Google (lo stesso di Google Drive)
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    // Riconoscimento del testo sul telefono, per il N. Progressivo
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
