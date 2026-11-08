plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.vite)
    alias(libs.plugins.metro)
}

kotlin {
    jvmToolchain(21)
    js(IR) {
        browser()
        useEsModules()
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":api"))
            implementation(project(":presentation"))
            implementation(compose.runtime)
            implementation(compose.html.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

kotlinVite {
    configFilePath.set(file("vite.config.js"))
}
