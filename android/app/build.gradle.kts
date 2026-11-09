import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.pi.morse"
    compileSdk = 36

    defaultConfig {
        applicationId = "br.pi.morse"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "3.3"
        buildConfigField("String", "REPO", "\"H44TAKE/morse-pi\"")
    }

    // Chave de assinatura própria (fora do repositório). Sem ela o build cai na chave de debug.
    val sigFile = rootProject.file("../chaves/assinatura.properties")
    val sig = Properties().apply { if (sigFile.exists()) sigFile.inputStream().use { load(it) } }
    signingConfigs {
        if (sigFile.exists()) create("morse") {
            storeFile = file(sig.getProperty("storeFile"))
            storePassword = sig.getProperty("storePassword")
            keyAlias = sig.getProperty("keyAlias")
            keyPassword = sig.getProperty("keyPassword")
        }
    }

    buildFeatures { buildConfig = true }

    // Dois apps do mesmo código: "controle" (o que já existia) e "rosto" (a carinha).
    flavorDimensions += "papel"
    productFlavors {
        create("controle") {
            dimension = "papel"
            resValue("string", "app_name", "Morse Pi")
            buildConfigField("String", "APK_NAME", "\"MorsePi.apk\"")
        }
        create("rosto") {
            dimension = "papel"
            applicationIdSuffix = ".rosto"
            resValue("string", "app_name", "Morse Rosto")
            buildConfigField("String", "APK_NAME", "\"MorseRosto.apk\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("morse") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
