plugins {
    id("com.android.application") version "8.5.2" apply false
    // LiteRT-LM 0.18.0 is compiled with Kotlin 2.4; match its metadata version.
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
}
