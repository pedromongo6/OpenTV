// language: Kotlin DSL, file: build.gradle.kts
// *Root build file. Plugin versions declared once, applied per-module.*

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
}
