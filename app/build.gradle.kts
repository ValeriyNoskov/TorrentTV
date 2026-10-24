plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.torrenttv.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.torrenttv.app"
        minSdk = 29          // Android 10 — минимальная версия Android TV по требованию
        targetSdk = 34
        versionCode = 99
        versionName = "0.7.83"
        // Ключи метаданных задаются в gradle.properties: kpApiKey=... (kinopoisk.dev) или tmdbApiKey=...
        buildConfigField("String", "KP_API_KEY", "\"${findProperty("kpApiKey") ?: ""}\"")
        buildConfigField("String", "TMDB_API_KEY", "\"${findProperty("tmdbApiKey") ?: ""}\"")
        // GitHub-токен для приватного репо (автообновление + Gist-логи).
        // В CI передаётся секретом: -PghEmbeddedToken=$GH_EMBEDDED_TOKEN
        buildConfigField("String", "GH_EMBEDDED_TOKEN", "\"${findProperty("ghEmbeddedToken") ?: ""}\"")
    }

    // Подпись релиза: ключ берётся из переменных окружения (CI) или gradle.properties;
    // если ключа нет (локальная сборка без настройки) — fallback на debug-подпись
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("VNOSKINO_KEYSTORE")
                ?: (findProperty("vnoskinoKeystore") as String?)
            if (ksPath != null && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("VNOSKINO_STORE_PASSWORD")
                    ?: (findProperty("vnoskinoStorePassword") as String? ?: "")
                keyAlias = System.getenv("VNOSKINO_KEY_ALIAS")
                    ?: (findProperty("vnoskinoKeyAlias") as String? ?: "vnoskino")
                keyPassword = System.getenv("VNOSKINO_KEY_PASSWORD")
                    ?: (findProperty("vnoskinoKeyPassword") as String? ?: "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val ks = signingConfigs.getByName("release")
            signingConfig = if (ks.storeFile?.exists() == true) ks else signingConfigs.getByName("debug")
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

    lint {
        // media3 UnstableApi: TorrentDataSource/кастомная панель плеера сознательно
        // используют нестабильные API media3 (DataSource, ProgressiveMediaSource) —
        // публичного стабильного аналога нет. RestrictedApi: ComponentActivity
        // .dispatchKeyEvent — публичный метод со строгой аннотацией библиотеки.
        disable += "UnsafeOptInUsageError"
        disable += "RestrictedApi"
    }
}

dependencies {
    // Compose for TV (стабильная версия)
    implementation("androidx.tv:tv-material:1.0.0")

    // Compose базовый
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.compose.ui:ui:1.7.4")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.4")
    implementation("androidx.compose.foundation:foundation:1.7.4")
    implementation("androidx.compose.material:material-icons-core:1.7.4")

    // Загрузка постеров
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Сеть (TMDB, GitHub-обновления)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Unit-тесты
    testImplementation("junit:junit:4.13.2")
    // XmlPullParserFactory в JVM-тестах (для EpgService / M3uParser)
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    // org.json в JVM-тестах
    testImplementation("org.json:json:20240303")

    // Торрент-движок (jlibtorrent = JNI-обёртка над libtorrent)
    val jlibtorrent = "2.0.12.9"
    implementation("com.frostwire:jlibtorrent:$jlibtorrent")
    implementation("com.frostwire:jlibtorrent-android-arm:$jlibtorrent")
    implementation("com.frostwire:jlibtorrent-android-arm64:$jlibtorrent")
    implementation("com.frostwire:jlibtorrent-android-x86:$jlibtorrent")
    implementation("com.frostwire:jlibtorrent-android-x86_64:$jlibtorrent")

    // Плеер (Media3 / ExoPlayer)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")  // IPTV-потоки (.m3u8)
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1") // Filmix отдаёт .mpd (DASH) — без него краш ClassNotFoundException
    implementation("androidx.media3:media3-ui:1.4.1")
}
