package homegap.web.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import homegap.api.ActionResponse
import homegap.presentation.app.AppPresenter
import homegap.presentation.data.ApiException
import homegap.presentation.data.AppCoroutineScope
import homegap.presentation.data.TokenStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.bearerAuth
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * The dashboard's object graph. Browser implementations (fetch API, localStorage, Date) are
 * contributed with `@ContributesBinding`; presenters come from the presentation module.
 */
@DependencyGraph(AppScope::class)
interface WebAppGraph {
    val appPresenter: AppPresenter

    @Provides
    @SingleIn(AppScope::class)
    fun json(): Json {
        return Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    @Provides
    @SingleIn(AppScope::class)
    fun httpClient(json: Json, tokenStore: TokenStore): HttpClient {
        return HttpClient(Js) {
            defaultRequest {
                headers {
                    tokenStore.token?.let(::bearerAuth)
                }
            }
            install(JsonBodyContentType)
            install(SSE)
            HttpResponseValidator {
                validateResponse { response ->
                    if (response.status.isSuccess()) return@validateResponse
                    val message = runCatching {
                        json.decodeFromString<ActionResponse>(response.bodyAsText()).message
                    }.getOrNull()
                    throw ApiException(response.status.value, message ?: "HTTP ${response.status}")
                }
            }
            install(ContentNegotiation) {
                json(json = json)
            }
        }
    }

    @Provides
    @SingleIn(AppScope::class)
    @AppCoroutineScope
    fun appScope(): CoroutineScope = MainScope()

    @Provides
    fun clock(): Clock = Clock.System
}

private val JsonBodyContentType = createClientPlugin("JsonBodyContentType") {
    onRequest { request, content ->
        if (content !is OutgoingContent && request.contentType() == null) {
            request.contentType(ContentType.Application.Json)
        }
    }
}
