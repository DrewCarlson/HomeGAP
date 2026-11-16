package homegap.identity

import homegap.api.ActionResponse
import homegap.api.ApiJson
import homegap.api.CreateIdentityUserRequest
import homegap.api.IdentityPasswordResponse
import homegap.api.IdentityUserDto
import homegap.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking

/**
 * `homegap user`: manages the users of HomeGAP's own sign-in from a shell in the orchestrator
 * container. It calls the API of the dashboard, so the orchestrator stays the one writer.
 */
class UserCommand(config: AppConfig, private val args: List<String>) {
    private companion object {
        const val USAGE = "homegap user list | add EMAIL | password EMAIL | block EMAIL | unblock EMAIL | remove EMAIL"
    }

    private val api = DashboardApi(config, "/api/identity/users")

    fun run(): Unit = runBlocking {
        HttpClient(CIO).use { http ->
            when (val action = args.firstOrNull()) {
                "list" -> users(http).forEach { println(describe(it)) }
                "add" -> {
                    val request = CreateIdentityUserRequest(email(), option("--name").orEmpty(), options("--role"))
                    val body = call(http, HttpMethod.Post, "", ApiJson.encodeToString(request))
                    showPassword(ApiJson.decodeFromString<IdentityPasswordResponse>(body))
                }
                "password" -> {
                    val body = call(http, HttpMethod.Post, "/${id(http)}/password")
                    showPassword(ApiJson.decodeFromString<IdentityPasswordResponse>(body))
                }
                "block", "unblock" -> showMessage(call(http, HttpMethod.Post, "/${id(http)}/$action"))
                "remove" -> showMessage(call(http, HttpMethod.Delete, "/${id(http)}"))
                else -> error(USAGE)
            }
        }
    }

    private fun describe(user: IdentityUserDto): String {
        val state = if (user.blocked) "blocked" else "active"
        val source = user.provider?.let { "  roles from $it" }.orEmpty()
        return "${user.email}  ${user.roles.joinToString(",")}  $state$source"
    }

    private fun showPassword(response: IdentityPasswordResponse) {
        println(response.message)
        println("password: ${response.password}")
        println("HomeGAP does not keep the password. Give it to the user now.")
    }

    private fun showMessage(body: String) = println(ApiJson.decodeFromString<ActionResponse>(body).message)

    private fun email(): String = args.getOrNull(1)?.takeIf { !it.startsWith("--") } ?: error("EMAIL required")

    private fun option(name: String): String? = options(name).firstOrNull()

    private fun options(name: String): List<String> =
        args.indices.filter { args[it] == name }.mapNotNull { args.getOrNull(it + 1) }

    private suspend fun call(http: HttpClient, method: HttpMethod, path: String, body: String? = null): String =
        api.call(http, method, path, body)

    private suspend fun users(http: HttpClient): List<IdentityUserDto> =
        ApiJson.decodeFromString(call(http, HttpMethod.Get, ""))

    private suspend fun id(http: HttpClient): String {
        val email = email()
        return users(http).firstOrNull { it.email.equals(email, ignoreCase = true) }?.id ?: error("no user $email")
    }
}
