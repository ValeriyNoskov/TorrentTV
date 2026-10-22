plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.torrenttv.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.torrenttv.app"
        minSdk = 21          // Android 5.0 — покрывает почти все Android TV
        targetSdk = 34
        versionCode = 1
        versionName = "0.2.0"
        // Ключ TMDB задаётся в gradle.properties: tmdbApiKey=...
        buildConfigField("String", "TMDB_API_KEY", "\"${findProperty("tmdbApiKey") ?: ""}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true   // нужна версия в BuildConfig.VERSION_NAME для автообновления
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
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
    // Compose for TV
    implementation("androidx.tv:tv-foundation:1.0.0-alpha11")
    implementation("androidx.tv:tv-material:1.0.0-alpha11")

    // Compose базовый
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.8")
    implementation("androidx.compose.foundation:foundation:1.6.8")
    implementation("androidx.compose.material:material-icons-core:1.6.8")

    // Загрузка постеров
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Сеть (для поиска и TMDB — пригодится на следующем шаге)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.1")

    // Торрент-движок (подключим на этапе стриминга)
    // implementation("com.github.AriasC:jlhttp:...") — точную версию закрепим позже
}
