package homegap.identity

import homegap.api.ActionResponse
import homegap.api.ApiJson
import homegap.api.CreateIdentityMachineRequest
import homegap.api.IdentityMachineDto
import homegap.api.IdentityMachineSecretResponse
import homegap.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking

/** `homegap machine`: manages the devices with no user, from a shell in the orchestrator container. */
class MachineCommand(config: AppConfig, private val args: List<String>) {
    private val api = DashboardApi(config, "/api/identity/machines")

    fun run(): Unit = runBlocking {
        HttpClient(CIO).use { http ->
            when (args.firstOrNull()) {
                "list" -> machines(http).forEach { println("${it.name}  ${it.id}  ${it.roles.joinToString(",")}") }
                "add" -> {
                    val roles = args.indices.filter { args[it] == "--role" }.mapNotNull { args.getOrNull(it + 1) }
                    val request = ApiJson.encodeToString(CreateIdentityMachineRequest(name(), roles))
                    val answer = api.call(http, HttpMethod.Post, "", request)
                    val created = ApiJson.decodeFromString<IdentityMachineSecretResponse>(answer)
                    println(created.message)
                    println("id: ${created.machine.id}")
                    println("secret: ${created.secret}")
                    println("HomeGAP does not keep the secret. Give it to the machine now.")
                }
                "remove" -> {
                    val name = name()
                    val id = machines(http).firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
                        ?: error("no machine $name")
                    println(ApiJson.decodeFromString<ActionResponse>(api.call(http, HttpMethod.Delete, "/$id")).message)
                }
                else -> error("homegap machine list | add NAME [--role ROLE]... | remove NAME")
            }
        }
    }

    private fun name(): String = args.getOrNull(1)?.takeIf { !it.startsWith("--") } ?: error("NAME required")

    private suspend fun machines(http: HttpClient): List<IdentityMachineDto> =
        ApiJson.decodeFromString(api.call(http, HttpMethod.Get, ""))
}
