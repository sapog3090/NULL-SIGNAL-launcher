plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nullsignal.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nullsignal.launcher"
        minSdk = 26 // Android 8.0
        targetSdk = 35
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = 1 + run
        versionName = "0.1.$run"
    }

    // Один постоянный ключ: обновления ставятся поверх, прогресс не теряется
    signingConfigs {
        create("ns") {
            storeFile = rootProject.file("keystore/nullsignal.jks")
            storePassword = "nullsignal-public"
            keyAlias = "nullsignal"
            keyPassword = "nullsignal-public"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("ns") }
        release {
            // Без минификации: SSH (JSch) и др. грузят классы по имени — R8 их бы вырезал
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("ns")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.exifinterface)       // метаданные фото
    implementation(libs.zxing.core)          // генерация QR
    implementation(libs.gms.code.scanner)    // сканер QR без разрешения на камеру
    implementation(libs.jsch)                // SSH-клиент
    debugImplementation(libs.compose.ui.tooling)
}
