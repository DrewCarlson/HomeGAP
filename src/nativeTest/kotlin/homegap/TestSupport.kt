package homegap

import homegap.config.AppConfig
import homegap.config.ConfigLoader
import homegap.discovery.DockerContainerSummary
import homegap.platform.Crypto

val TEST_CONFIG = """
domain: test.example.net
data_dir: /tmp/homegap-test
bunny:
  zone: example.net
  api_key_env: HOMEGAP_TEST_BUNNY
nodes:
  - id: na1
    region: NA
    host: 203.0.113.10
    ssh_key_file: /dev/null
    location: { latitude: 45.5, longitude: -73.6 }
  - id: as1
    region: AS
    host: 198.51.100.20
    public_ipv6: "2001:db8::20"
    ssh_key_file: /dev/null
""".trimIndent()

fun testConfig(): AppConfig = ConfigLoader.parse(TEST_CONFIG)

fun container(name: String, vararg labels: Pair<String, String>) =
    DockerContainerSummary(id = "id-$name", names = listOf("/$name"), labels = labels.toMap())

fun tmpPath(prefix: String) = "/tmp/homegap-test-$prefix-${Crypto.token(8)}"

val TRAEFIK = container(
    "traefik",
    "homegap.expose" to "test.example.net",
    "homegap.wildcard" to "true",
    "homegap.protocol" to "https",
    "homegap.tls" to "passthrough",
)
