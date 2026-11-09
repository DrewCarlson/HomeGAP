package homegap.presentation.data

import homegap.api.*

/** The orchestrator's `/api`, as used by presenters. Implemented over fetch in the web module. */
interface ControllerApi {
    suspend fun status(): StatusSnapshot
    suspend fun diff(): DiffResponse
    suspend fun secrets(): SecretsResponse
    suspend fun config(): ConfigResponse
    suspend fun speedtest(): SpeedTestOverview

    /** Follows `/api/events` until the stream ends, calling [onEvent] for each event. */
    suspend fun events(afterSeq: Long, onEvent: (ServerEvent) -> Unit)

    suspend fun resync(): ActionResponse
    suspend fun provision(node: String): ActionResponse
    suspend fun retryUpdate(node: String): ActionResponse
    suspend fun skipUpdate(node: String): ActionResponse
    suspend fun pause(scope: PauseScope, id: String?, paused: Boolean): ActionResponse
    suspend fun addNode(node: NodeConfigDto): ActionResponse
    suspend fun removeNode(id: String): ActionResponse
    suspend fun decommission(id: String, confirm: String): ActionResponse
    suspend fun speedLink(): SpeedTestLinkDto
    suspend fun runTunnelTest(node: String, streams: Int): SpeedTestResultDto

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
enum class PauseScope(val wire: String) { Global("global"), Node("node"), Service("service") }

/** An error response from the API. */
class ApiException(val status: Int, message: String) : Exception(message) {
    val unauthorized: Boolean get() = status == 401
}
