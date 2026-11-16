package homegap

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import homegap.config.AppConfig
import homegap.config.ConfigLoader
import homegap.discovery.DockerContainerSummary
import homegap.platform.Crypto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

private val YAML_INT = Regex("^-?(0|[1-9]\\d*)$")

/** The YAML [text] as a JSON tree, so a test reads a rendered file by its keys. */
fun yamlToJson(text: String): JsonElement = toJson(Yaml.default.parseToYamlNode(text))

private fun toJson(node: YamlNode): JsonElement = when (node) {
    is YamlMap -> JsonObject(node.entries.entries.associate { (key, value) -> key.content to toJson(value) })
    is YamlList -> JsonArray(node.items.map { toJson(it) })
    is YamlNull -> JsonNull
    is YamlTaggedNode -> toJson(node.innerNode)
    is YamlScalar -> when {
        YAML_INT.matches(node.content) -> JsonPrimitive(node.content.toLong())
        node.content == "true" -> JsonPrimitive(true)
        node.content == "false" -> JsonPrimitive(false)
        else -> JsonPrimitive(node.content)
    }
}
