package homegap

import homegap.api.LocationDto
import homegap.config.ConfigLoader
import homegap.dashboard.MapFiles
import homegap.locations.LocationCatalog
import homegap.platform.Files
import homegap.status.PublicStatus
import homegap.status.StatusPage
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.*

class MapsTest {
    @Test
    fun tilesAreServedByRangeOnly() = testApplication {
        val file = tmpPath("tiles")
        Files.writeAtomic(file, "0123456789")
        application { routing { get(MapFiles.TILES_PATH) { with(MapFiles) { serveTiles(file) } } } }

        val part = client.get(MapFiles.TILES_PATH) { header(HttpHeaders.Range, "bytes=2-5") }
        assertEquals(HttpStatusCode.PartialContent, part.status)
        assertEquals("2345", part.bodyAsText())
        assertEquals("bytes 2-5/10", part.headers[HttpHeaders.ContentRange])

        val tail = client.get(MapFiles.TILES_PATH) { header(HttpHeaders.Range, "bytes=7-") }
        assertEquals("789", tail.bodyAsText(), "an open range runs to the end")
        val whole = client.get(MapFiles.TILES_PATH)
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, whole.status, "never the whole file")
        val pastTheEnd = client.get(MapFiles.TILES_PATH) { header(HttpHeaders.Range, "bytes=20-30") }
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, pastTheEnd.status)
    }

    @Test
    fun missingTilesAreANotFound() = testApplication {
        application {
            routing {
                get(MapFiles.TILES_PATH) { with(MapFiles) { serveTiles("/nonexistent/world.pmtiles") } }
            }
        }
        val response = client.get(MapFiles.TILES_PATH) { header(HttpHeaders.Range, "bytes=0-6") }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun fontPathsAreDecoded() {
        assertEquals(
            "/map/fonts/Noto Sans Regular/0-255.pbf",
            MapFiles.decode("/map/fonts/Noto%20Sans%20Regular/0-255.pbf"),
        )
        assertEquals("/map/plain.js", MapFiles.decode("/map/plain.js"))
        assertNull(MapFiles.asset("/map/../index.html"), "nothing outside /map/")
        assertNull(MapFiles.asset("/assets/index.js"))
    }

    private fun newCatalog(): LocationCatalog =
        LocationCatalog(ConfigLoader.parse(TEST_CONFIG).copy(dataDir = tmpPath("data")))

    @Test
    fun theShippedCatalogIsWellFormed() {
        val all = newCatalog().all()
        assertTrue(all.size > 80, "${all.size} locations")
        assertEquals(all.size, all.map { it.id }.toSet().size, "ids are unique")
        assertTrue(all.all { it.builtIn && !it.edited })
        assertTrue(
            all.all {
                it.region in LocationCatalog.REGIONS && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
            },
        )
        val dallas = all.single { it.id == "us-dallas" }
        assertEquals("Dallas, Texas", dallas.name)
        assertTrue("Vultr" in dallas.providers)
    }

    @Test
    fun localEditsWinAndShippedEntriesCanBeHiddenOrRestored() {
        val catalog = newCatalog()
        val dallas = catalog.all().single { it.id == "us-dallas" }
        catalog.put(dallas.copy(name = "Dallas–Fort Worth"))
        assertEquals("Dallas–Fort Worth", catalog.all().single { it.id == "us-dallas" }.name)
        assertTrue(catalog.all().single { it.id == "us-dallas" }.edited)
        catalog.delete("us-dallas", reset = true)
        assertEquals("Dallas, Texas", catalog.all().single { it.id == "us-dallas" }.name)

        catalog.delete("us-dallas")
        assertTrue(catalog.all().none { it.id == "us-dallas" }, "a shipped entry is hidden")

        catalog.put(
            LocationDto(
                id = "home-lab-town",
                name = "Springfield",
                country = "United States",
                countryCode = "us",
                region = "na",
                latitude = 39.8,
                longitude = -89.6,
            ),
        )
        val added = catalog.all().single { it.id == "home-lab-town" }
        assertFalse(added.builtIn)
        assertEquals("US", added.countryCode)
        assertEquals("NA", added.region)
        catalog.delete("home-lab-town")
        assertTrue(catalog.all().none { it.id == "home-lab-town" })

        assertFailsWith<IllegalArgumentException> {
            catalog.put(LocationDto(id = "Bad Id", name = "x", country = "", latitude = 0.0, longitude = 0.0))
        }
        assertFailsWith<IllegalArgumentException> {
            catalog.put(LocationDto(id = "ok", name = "x", country = "", latitude = 95.0, longitude = 0.0))
        }
        assertFailsWith<IllegalArgumentException> { catalog.delete("never-existed") }
    }

    @Test
    fun thePublicStatusShowsOnlyTheHomelabsRegion() {
        val location = "{ latitude: 32.78, longitude: -96.8, name: \"Dallas, Texas\" }"
        val config = ConfigLoader.parse("$TEST_CONFIG\nhomelab:\n  location: $location\n")
        val home = assertNotNull(PublicStatus.homeRegion(config))
        assertEquals("NA", home.region)
        assertEquals("Home · North America", home.label)
        assertNotEquals(32.78, home.lat, "never the real point")
        assertNotEquals(-96.8, home.lon)
        assertNull(PublicStatus.homeRegion(ConfigLoader.parse(TEST_CONFIG)))
    }

    @Test
    fun theStatusPageLoadsNothingFromElsewhere() {
        val html = StatusPage.HTML
        assertFalse(Regex("""(src|href)=["']https?://""").containsMatchIn(html), "no external scripts or styles")
        assertTrue("import('/map/homegap-map.js')" in html, "the map comes from this origin")
    }

    @Test
    fun mapCodeIsRevalidatedNotKeptStale() = testApplication {
        application { routing { get("/map/{path...}") { with(MapFiles) { serveMapAsset() } } } }
        // The binary has the map files only when the build includes the dashboard.
        val first = client.get("/map/homegap-map.js")
        if (first.status == HttpStatusCode.NotFound) return@testApplication
        assertEquals("no-cache", first.headers[HttpHeaders.CacheControl])
        val etag = assertNotNull(first.headers[HttpHeaders.ETag])
        val current = client.get("/map/homegap-map.js") { header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.NotModified, current.status)
        val stale = client.get("/map/homegap-map.js") { header(HttpHeaders.IfNoneMatch, "\"stale\"") }
        assertEquals(HttpStatusCode.OK, stale.status)
    }
}
