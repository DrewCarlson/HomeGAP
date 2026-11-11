package homegap.web.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.*
import homegap.presentation.data.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.sse.deserialize
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/** [ControllerApi] over ktor's browser client, against the page's own origin (bound by Metro). */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class FetchControllerApi(
    private val http: HttpClient,
    private val json: Json,
) : ControllerApi {

    override suspend fun status(): StatusSnapshot = http.get("/api/status").body()
    override suspend fun diff(): DiffResponse = http.get("/api/diff").body()
    override suspend fun secrets(): SecretsResponse = http.get("/api/secrets").body()
    override suspend fun config(): ConfigResponse = http.get("/api/config").body()
    override suspend fun speedtest(): SpeedTestOverview = http.get("/api/speedtest").body()
    override suspend fun log(
        beforeSeq: Long?,
        limit: Int,
        level: LogLevel?,
        domain: String?,
        query: String?,
    ): LogResponse = http.get("/api/log") {
        beforeSeq?.let { parameter("before", it) }
        parameter("limit", limit)
        level?.let { parameter("level", it.name) }
        domain?.let { parameter("domain", it) }
        query?.takeIf { it.isNotBlank() }?.let { parameter("q", it) }
    }.body()

    override suspend fun search(query: String): SearchResponse =
        http.get("/api/search") { parameter("q", query) }.body()

    override suspend fun metrics(rangeSeconds: Long, edge: String?): MetricsResponse =
        http.get("/api/metrics") {
            parameter("range", rangeSeconds)
            edge?.let { parameter("edge", it) }
        }.body()

    /** Uses ktor's SSE client rather than EventSource so the bearer token can be sent. */
    override suspend fun events(afterSeq: Long): Flow<ServerEvent> = flow {
        try {
            http.sse(
                "/api/events?after=$afterSeq",
                deserialize = { type, data -> json.decodeFromString(json.serializersModule.serializer(type.kotlinType!!), data) },
            ) {
                incoming.collect { event ->
                    // Comment-only events (": keepalive") carry no data.
                    deserialize<ServerEvent>(event)?.let { emit(it) }
                }
            }
        } catch (e: SSEClientException) {
            (e.cause as? ApiException)?.let { throw it }
            val status = e.response?.status ?: throw e
            throw ApiException(status.value, "HTTP $status")
        }
    }

    override suspend fun resync(): ActionResponse = http.post("/api/resync").body()
    override suspend fun applyOnce(): ActionResponse = http.post("/api/resync") { parameter("apply", "once") }.body()
    override suspend fun restartTunnel(node: String): ActionResponse = http.post("/api/tunnels/$node/restart").body()
    override suspend fun undo(id: String): ActionResponse = http.post("/api/undo/$id").body()
    override suspend fun reissueInvitation(id: String): CreateAccessUserResponse =
        http.post("/api/access/invitations/$id/reissue").body()
    override suspend fun provision(node: String): ActionResponse = http.post("/api/nodes/$node/provision").body()
    override suspend fun retryUpdate(node: String): ActionResponse = http.post("/api/nodes/$node/update/retry").body()
    override suspend fun skipUpdate(node: String): ActionResponse = http.post("/api/nodes/$node/update/skip").body()

    override suspend fun pause(scope: PauseScope, id: String?, paused: Boolean): ActionResponse =
        http.post("/api/pause") {
            setBody(PauseRequest(scope.wire, id, paused))
        }.body()

    override suspend fun addNode(node: NodeConfigDto): ActionResponse =
        http.post("/api/nodes") { setBody(node) }.body()
    override suspend fun removeNode(id: String): ActionResponse =
        http.delete("/api/nodes/$id").body()

    override suspend fun decommission(id: String, confirm: String): ActionResponse =
        http.post("/api/nodes/$id/decommission") {
            parameter("confirm", confirm)
        }.body()

    override suspend fun speedLink(): SpeedTestLinkDto =
        http.post("/api/speedtest/link").body()

    override suspend fun startSpeedRun(streams: Int): ActionResponse =
        http.post("/api/speedtest/runs") { parameter("streams", streams) }.body()

    override suspend fun cancelSpeedRun(): ActionResponse = http.delete("/api/speedtest/runs").body()

    override suspend fun runTunnelTest(node: String, streams: Int): SpeedTestResultDto =
        http.post("/api/speedtest/nodes/$node") {
            parameter("streams", streams)
        }.body()


    override suspend fun testConnection(login: SshLoginDto): ConnectionTestDto =
        http.post("/api/setup/test") { setBody(login) }.body()

    override suspend fun createKey(request: SshKeyRequestDto): SshKeyDto =
        http.post("/api/setup/keys") { setBody(request) }.body()

    override suspend fun startSetup(request: NodeSetupRequestDto): SetupJobDto =
        http.post("/api/setup/jobs") { setBody(request) }.body()

    override suspend fun setupJob(id: String): SetupJobDto = http.get("/api/setup/jobs/$id").body()

    override suspend fun nodeLogs(node: String, service: String, since: String, tail: Int): NodeTextDto =
        http.get("/api/nodes/$node/logs") {
            parameter("service", service)
            parameter("since", since)
            parameter("tail", tail)
        }.body()

    override suspend fun nodeDiagnostics(node: String): NodeTextDto =
        http.get("/api/nodes/$node/diagnostics").body()

    override suspend fun locations(): LocationsResponse = http.get("/api/locations").body()
    override suspend fun saveLocation(location: LocationDto): ActionResponse =
        http.put("/api/locations/${location.id}") { setBody(location) }.body()

    override suspend fun deleteLocation(id: String, reset: Boolean): ActionResponse =
        http.delete("/api/locations/$id") {
            if (reset) {
                parameter("reset", reset)
            }
        }.body()

    override suspend fun setHomelabLocation(place: PlaceDto?): ActionResponse =
        http.put("/api/homelab/location") {
            setBody(SetHomelabLocationRequest(place))
        }.body()

    override suspend fun setNodeLocation(id: String, place: PlaceDto): ActionResponse =
        http.put("/api/nodes/$id/location") {
            setBody(SetNodeLocationRequest(place))
        }.body()

    override suspend fun switchPrimary(to: String): ActionResponse =
        http.post("/api/cluster/switchover") {
            setBody(SwitchoverRequest(to))
        }.body()

    override suspend fun access(): AccessOverviewDto = http.get("/api/access").body()
    override suspend fun createUser(request: CreateAccessUserRequest): CreateAccessUserResponse =
        http.post("/api/access/users") {
            setBody(request)
        }.body()

    override suspend fun removeUser(id: String): ActionResponse =
        http.delete("/api/access/users/$id").body()

    override suspend fun setUserRoles(id: String, roleIds: List<Int>): ActionResponse =
        http.put("/api/access/users/$id/roles") {
            setBody(SetAccessRolesRequest(roleIds))
        }.body()

    override suspend fun removeInvitation(id: String): ActionResponse =
        http.delete("/api/access/invitations/$id").body()

    override suspend fun deviceAction(id: Int, action: String): ActionResponse =
        http.post("/api/access/devices/$id/$action").body()

    override suspend fun session(): SessionDto = http.get("/api/session").body()
    override suspend fun shareLinks(): ShareLinksResponse = http.get("/api/share-links").body()
    override suspend fun createShareLink(request: CreateShareLinkRequest): CreateShareLinkResponse =
        http.post("/api/share-links") {
            setBody(request)
        }.body()

    override suspend fun revokeShareLink(id: String): ActionResponse = http.delete("/api/share-links/$id").body()
}
