// Корневой build-файл проекта TorrentTV
plugins {
    // AGP 8.13 — последняя 8.x: умеет compileSdk 36 и не требует переезда на
    // встроенный Kotlin из AGP 9.x
    id("com.android.application") version "8.13.2" apply false
    // Kotlin 2.2: media3 1.11 собран Kotlin 2.2, старый компилятор 1.9
    // не читает его метаданные («incompatible version of Kotlin»)
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    // С Kotlin 2.x компилятор Compose подключается отдельным плагином
    // (вместо composeOptions.kotlinCompilerExtensionVersion)
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}
