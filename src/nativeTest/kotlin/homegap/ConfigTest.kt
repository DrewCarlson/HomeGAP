package homegap

import homegap.config.ConfigLoader
import homegap.config.parseDuration
import homegap.platform.Files
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ConfigTest {
    @Test
    fun exampleConfigIsValid() {
        // test binaries run from the project root
        val path = listOf("config/config.example.yml", "../config/config.example.yml").first { Files.exists(it) }
        val c = ConfigLoader.load(path)
        assertEquals(2, c.nodes.size)
        assertEquals(60.seconds, c.reconcile.resync)
        assertTrue(c.acme.caa)
        assertEquals(homegap.config.AcmeConfig.PRODUCTION, c.acme.directoryUrl())
    }

    @Test
    fun validationCatchesMistakes() {
        val bad = TEST_CONFIG.replace("zone: example.net", "zone: other.invalid")
        assertFailsWith<IllegalArgumentException> { ConfigLoader.parse(bad) }.also { assertTrue("bunny.zone" in it.message!!) }
        val dup = TEST_CONFIG + "\n  - id: na1\n    host: 192.0.2.1\n    region: EU\n    ssh_key_file: /dev/null\n"
        assertFailsWith<IllegalArgumentException> { ConfigLoader.parse(dup) }.also { assertTrue("duplicate" in it.message!!) }
        val noGeo = TEST_CONFIG.replace("region: AS", "region: XX")
        assertFailsWith<IllegalArgumentException> { ConfigLoader.parse(noGeo) }
        assertFailsWith<Exception> { ConfigLoader.parse(TEST_CONFIG + "\nunknown_key: 1\n") }
    }

    @Test
    fun saveRoundTrips() {
        val c = testConfig()
        val p = tmpPath("cfg") + ".yml"
        ConfigLoader.save(p, c)
        assertEquals(c, ConfigLoader.load(p))
    }

    @Test
    fun durations() {
        assertEquals(500.milliseconds, parseDuration("500ms"))
        assertEquals(2.minutes, parseDuration("2m"))
        assertEquals(30.seconds, parseDuration("30"))
    }
}
