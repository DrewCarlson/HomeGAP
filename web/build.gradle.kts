import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

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
    js {
        // Disabled test task keeps Karma (a GitHub tarball) out of the npm install,
        // this prevents occasional CI failures due to network service outages.
        browser { testTask { enabled = false } }
        useEsModules()
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":api"))
            implementation(project(":presentation"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.js)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.compose.runtime)
            implementation(libs.compose.html.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(npm("maplibre-gl", libs.versions.maplibre.get()))
            implementation(npm("pmtiles", libs.versions.pmtiles.get()))
            implementation(npm("@protomaps/basemaps", libs.versions.protomaps.basemaps.get()))
        }
    }
}

kotlinVite {
    configFilePath.set(file("vite.config.js"))
}

// ---------------------------------------------------------------- map files
// Served under /map/: the libraries from npm, and the fonts and icons from protomaps/basemaps-assets
// (not published to npm), downloaded at a pinned commit and checked against web/map-assets.tsv.

val mapAssetsDir = layout.buildDirectory.dir("map-assets")

val downloadMapAssets by tasks.registering {
    val manifest = layout.projectDirectory.file("map-assets.tsv")
    val outDir = mapAssetsDir
    inputs.file(manifest)
    outputs.dir(outDir)
    doLast {
        val lines = manifest.asFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val commit = lines.first().removePrefix("commit ").trim()
        val sha256 = { bytes: ByteArray ->
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
        for (line in lines.drop(1)) {
            val (hash, remote, local) = line.split('\t')
            val target = outDir.get().asFile.resolve(local)
            if (target.isFile && sha256(target.readBytes()) == hash) continue
            val url = "https://raw.githubusercontent.com/protomaps/basemaps-assets/$commit/" +
                remote.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            val bytes = URI(url).toURL().openStream().use { it.readBytes() }
            check(sha256(bytes) == hash) { "$remote from basemaps-assets@$commit does not match web/map-assets.tsv" }
            target.parentFile.mkdirs()
            target.writeBytes(bytes)
        }
    }
}

tasks.named<Copy>("jsProcessResources") {
    dependsOn(":kotlinNpmInstall", downloadMapAssets)
    val modules = rootProject.layout.buildDirectory.dir("js/node_modules")
    from(modules.map { it.dir("maplibre-gl/dist") }) {
        include("maplibre-gl.mjs", "maplibre-gl-worker.mjs", "maplibre-gl.css")
        // Their source map comments point at files that aren't served.
        filter { line -> if (line.startsWith("//# sourceMappingURL=")) "" else line }
        into("public/map/lib")
    }
    from(modules.map { it.file("maplibre-gl/LICENSE.txt") }) { rename { "LICENSE-maplibre.txt" }; into("public/map/lib") }
    // pmtiles' ES module imports fflate by name, which a browser can't resolve: use its bundled script.
    from(modules.map { it.file("pmtiles/dist/pmtiles.js") }) {
        filter { line -> if (line.startsWith("//# sourceMappingURL=")) "" else line }
        into("public/map/lib")
    }
    from(modules.map { it.file("@protomaps/basemaps/dist/esm/index.js") }) {
        rename { "basemaps.mjs" }
        filter { line -> if (line.startsWith("//# sourceMappingURL=")) "" else line }
        into("public/map/lib")
    }
    from(mapAssetsDir) { into("public/map") }
}
