package homegap

import homegap.config.AcmeConfig
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
        // The test binaries run from the project root.
        val path = listOf("config/config.example.yml", "../config/config.example.yml").first { Files.exists(it) }
        val config = ConfigLoader.load(path)
        assertEquals(2, config.nodes.size)
        assertEquals(60.seconds, config.reconcile.resync)
        assertTrue(config.acme.caa)
        assertEquals(AcmeConfig.PRODUCTION, config.acme.directoryUrl())
    }

    @Test
    fun validationCatchesMistakes() {
        val bad = TEST_CONFIG.replace("zone: example.net", "zone: other.invalid")
        val wrongZone = assertFailsWith<IllegalArgumentException> { ConfigLoader.parse(bad) }
        assertTrue("bunny.zone" in wrongZone.message!!)
        val duplicate = "$TEST_CONFIG\n  - id: na1\n    host: 192.0.2.1\n    region: EU\n    ssh_key_file: /dev/null\n"
        val duplicateId = assertFailsWith<IllegalArgumentException> { ConfigLoader.parse(duplicate) }
        assertTrue("duplicate" in duplicateId.message!!)
        val noGeo = TEST_CONFIG.replace("region: AS", "region: XX")
        assertFailsWith<IllegalArgumentException> { ConfigLoader.parse(noGeo) }
        assertFailsWith<Exception> { ConfigLoader.parse("$TEST_CONFIG\nunknown_key: 1\n") }
    }

    @Test
    fun saveRoundTrips() {
        val config = testConfig()
        val path = tmpPath("cfg") + ".yml"
        ConfigLoader.save(path, config)
        assertEquals(config, ConfigLoader.load(path))
    }

    @Test
    fun durations() {
        assertEquals(500.milliseconds, parseDuration("500ms"))
        assertEquals(2.minutes, parseDuration("2m"))
        assertEquals(30.seconds, parseDuration("30"))
    }

    @Test
    fun aNameIsRelativeToTheZone() {
        val config = testConfig()
        assertEquals("test", config.relativeName("test.example.net"))
        assertEquals("*.test", config.relativeName("*.test.example.net"))
        assertEquals("", config.relativeName("example.net"))
    }
}
