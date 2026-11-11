plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
    alias(libs.plugins.metro)
}

/**
 * Presenters and UI models for the dashboard: no DOM access, so they can be tested in Node and the
 * web module only renders their models.
 */
kotlin {
    jvmToolchain(21)
    js {
        browser { testTask { enabled = false } }
        nodejs()
    }

    sourceSets {
        all { languageSettings.optIn("kotlin.time.ExperimentalTime") }
        commonMain.dependencies {
            api(project(":api"))
            api(libs.compose.runtime)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.molecule.runtime)
        }
    }
}
