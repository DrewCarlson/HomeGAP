package homegap.presentation.data

import homegap.api.*
import kotlinx.coroutines.flow.Flow

/** The orchestrator's `/api`, as used by presenters. Implemented over fetch in the web module. */
interface ControllerApi {
    suspend fun status(): StatusSnapshot
    suspend fun diff(): DiffResponse
    suspend fun secrets(): SecretsResponse
    suspend fun config(): ConfigResponse
    suspend fun speedtest(): SpeedTestOverview

    /** Log entries older than [beforeSeq], newest first, filtered by [level], [domain] and [query]. */
    suspend fun log(
        beforeSeq: Long?,
        limit: Int,
        level: LogLevel?,
        domain: String?,
        query: String?,
    ): LogResponse

    /** What only the server can search: config keys, secrets and older log entries. */
    suspend fun search(query: String): SearchResponse

    /** The edges' graphs for the last [rangeSeconds], optionally only [edge]'s. */
    suspend fun metrics(rangeSeconds: Long, edge: String? = null): MetricsResponse

    /** Produces a [Flow] of events from `/api/events` until the stream ends. */
    suspend fun events(afterSeq: Long): Flow<ServerEvent>

    suspend fun resync(): ActionResponse

    /** One pass that applies the waiting changes although changes are paused. */
    suspend fun applyOnce(): ActionResponse

    /** Undoes an action whose response carried an undo id. */
    suspend fun undo(id: String): ActionResponse

    /** A new invite link for an open invitation. */
    suspend fun reissueInvitation(id: String): CreateAccessUserResponse

    /** Asks the tunnel supervisor to restart [node]'s connector. */
    suspend fun restartTunnel(node: String): ActionResponse
    suspend fun provision(node: String): ActionResponse
    suspend fun retryUpdate(node: String): ActionResponse
    suspend fun skipUpdate(node: String): ActionResponse
    suspend fun pause(scope: PauseScope, id: String?, paused: Boolean): ActionResponse
    suspend fun addNode(node: NodeConfigDto): ActionResponse
    suspend fun removeNode(id: String): ActionResponse
    suspend fun decommission(id: String, confirm: String): ActionResponse
    suspend fun speedLink(): SpeedTestLinkDto
    suspend fun runTunnelTest(node: String, streams: Int): SpeedTestResultDto

    /** Starts testing every node, one at a time, on the orchestrator. */
    suspend fun startSpeedRun(streams: Int): ActionResponse

    suspend fun cancelSpeedRun(): ActionResponse


    suspend fun testConnection(login: SshLoginDto): ConnectionTestDto
    suspend fun createKey(request: SshKeyRequestDto): SshKeyDto
    suspend fun startSetup(request: NodeSetupRequestDto): SetupJobDto
    suspend fun setupJob(id: String): SetupJobDto

    /** A container's logs on [node] ([service] from [NodeLogServices]). */
    suspend fun nodeLogs(node: String, service: String, since: String, tail: Int): NodeTextDto

    /** The node's read-only diagnostics bundle. */
    suspend fun nodeDiagnostics(node: String): NodeTextDto

    /** The locations catalog and the homelab's location. */
    suspend fun locations(): LocationsResponse
    suspend fun saveLocation(location: LocationDto): ActionResponse

    /** Removes [id] (hides a shipped one); with [reset], restores an edited shipped one. */
    suspend fun deleteLocation(id: String, reset: Boolean): ActionResponse
    suspend fun setHomelabLocation(place: PlaceDto?): ActionResponse

    /** Moves node [id] on the maps and in geo DNS steering. */
    suspend fun setNodeLocation(id: String, place: PlaceDto): ActionResponse

    /** Makes cluster member [to] the database primary now. */
    suspend fun switchPrimary(to: String): ActionResponse

    suspend fun access(): AccessOverviewDto
    suspend fun createUser(request: CreateAccessUserRequest): CreateAccessUserResponse
    suspend fun removeUser(id: String): ActionResponse
    suspend fun setUserRoles(id: String, roleIds: List<Int>): ActionResponse
    suspend fun removeInvitation(id: String): ActionResponse

    /** `archive`, `unarchive`, `block`, `unblock` or `delete`. */
    suspend fun deviceAction(id: Int, action: String): ActionResponse
}

/** What a pause request applies to (`PauseRequest.scope` on the wire). */
enum class PauseScope(val wire: String) {
    Global("global"),
    Node("node"),
    Service("service")
}

/** An error response from the API. */
class ApiException(val status: Int, message: String) : Exception(message) {
    val unauthorized: Boolean get() = status == 401
    val forbidden: Boolean get() = status == 403
}
