package homegap.routes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import homegap.access.AccessService
import homegap.api.ApiJson
import homegap.api.LogLevel
import homegap.api.LogResponse
import homegap.api.MeshPeerDto
import homegap.api.MeshProbeDto
import homegap.api.MetricsResponse
import homegap.api.ServerEvent
import homegap.api.SetupJobDto
import homegap.api.SpeedTestLiveDto
import homegap.api.name
import homegap.api.StatusSnapshot
import homegap.api.snapshotFields
import homegap.api.snapshotPatch
import homegap.platform.nowMillis
import homegap.reconcile.Controller
import homegap.setup.NodeSetupService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The status snapshot and the event stream that pushes it, with the reconcile log. */
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class StatusRoutes(
    private val controller: Controller,
    private val setup: NodeSetupService,
    private val access: AccessService,
) : RoutingController {
    override fun init(parent: Route) {
        parent.get("/status") { getStatus() }
        parent.get("/events") { streamEvents() }
        parent.get("/metrics") { getMetrics() }
    }

    /** The edges' graphs: every series for the last `?range=` seconds (an hour by default, at most a day). */
    suspend fun RoutingContext.getMetrics() {
        val range = (call.request.queryParameters["range"]?.toLongOrNull() ?: 3600L).coerceIn(60L, 86_400L)
        val since = nowMillis() / 1000 - range
        val series = controller.metrics.range(
            sinceSeconds = since,
            edge = call.request.queryParameters["edge"],
        )
        call.respond(MetricsResponse(series))
    }

    suspend fun RoutingContext.getStatus() {
        call.respond(controller.status.value ?: controller.refreshStatus())
    }

    /**
     * Server-sent events: `status` (the whole snapshot, first), then `patch` (only the top-level
     * fields that changed) and `log` (entries after `?after=SEQ`). Tunnel status comes from files the supervisor writes, so the snapshot is
     * also re-checked every few seconds without a change signal.
     */
    suspend fun RoutingContext.streamEvents() {
        var lastSeq = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L
        // A client that saw a previous controller process has a sequence ahead of ours.
        if (lastSeq > controller.log.revision.value) lastSeq = 0L
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header("X-Accel-Buffering", "no")
        call.respondBytesWriter(ContentType.Text.EventStream) { writeEvents(afterSeq = lastSeq) }
    }

    private suspend fun ByteWriteChannel.writeEvents(afterSeq: Long) = coroutineScope {
        var lastSeq = afterSeq
        var sentFields: JsonObject? = null
        var sentState: StatusSnapshot? = null
        var sentAt = 0L
        var lastWrite = 0L
        // New samples go out as they are taken; the client loads the history itself.
        var sentMetrics = controller.metrics.revision.value
        // Setup jobs go out whole whenever they change; the first time, every job this process knows.
        val sentJobs = mutableMapOf<String, SetupJobDto>()
        var sentAccess = access.revision.value
        var sentLive: SpeedTestLiveDto? = null
        // The controller publishes the status; this stream only reads it.
        val published = controller.status.map { it?.generatedAt ?: 0L }
        val changes = merge(published, controller.log.revision, setup.changes, access.revision).conflate().produceIn(this)
        try {
            while (true) {
                val snapshot = controller.status.value ?: controller.refreshStatus()
                val state = snapshot.withoutLiveValues()
                val now = nowMillis()
                val previous = sentFields
                if (previous == null) {
                    writeEvent(ServerEvent.Status(snapshot))
                    sentFields = snapshotFields(snapshot)
                    sentState = state
                    sentAt = now
                    lastWrite = now
                } else if (state != sentState || now - sentAt >= LIVE_REFRESH.inWholeMilliseconds) {
                    // A state change goes out at once; round trips and byte counts alone wait for
                    // the next refresh, so a quiet system sends one small patch per refresh.
                    val fields = snapshotFields(snapshot)
                    snapshotPatch(
                        previous = previous,
                        current = fields,
                        at = snapshot.generatedAt,
                    )?.let { patch ->
                        writeEvent(patch)
                        lastWrite = now
                    }
                    sentFields = fields
                    sentState = state
                    sentAt = now
                }
                val metricsRevision = controller.metrics.revision.value
                if (metricsRevision != sentMetrics) {
                    controller.metrics.latest()?.let { batch ->
                        writeEvent(
                            ServerEvent.Metrics(
                                at = batch.atSeconds,
                                points = batch.points,
                            )
                        )
                        lastWrite = nowMillis()
                    }
                    sentMetrics = metricsRevision
                }
                for (job in setup.allJobs()) {
                    if (sentJobs[job.id] != job) {
                        writeEvent(ServerEvent.Job(job))
                        sentJobs[job.id] = job
                        lastWrite = nowMillis()
                    }
                }
                if (access.revision.value != sentAccess) {
                    sentAccess = access.revision.value
                    writeEvent(ServerEvent.Access(sentAccess))
                    lastWrite = nowMillis()
                }
                val live = controller.speedLive()
                if (live != sentLive) {
                    writeEvent(ServerEvent.SpeedTestLive(live))
                    sentLive = live
                    lastWrite = nowMillis()
                }
                val entries = controller.log.since(lastSeq)
                if (entries.isNotEmpty()) {
                    writeEvent(ServerEvent.Log(entries))
                    lastSeq = entries.last().seq
                    lastWrite = nowMillis()
                }
                if (nowMillis() - lastWrite > KEEPALIVE.inWholeMilliseconds) {
                    writeStringUtf8(": keepalive\n\n")
                    lastWrite = nowMillis()
                }
                flush()
                // While a speed test runs, its gauge moves a few times a second.
                withTimeoutOrNull(if (live != null) LIVE_TICK else RECHECK) { changes.receive() }
            }
        } finally {
            changes.cancel()
        }
    }

    private suspend fun ByteWriteChannel.writeEvent(event: ServerEvent) {
        writeStringUtf8("event: ${event.name}\ndata: ${ApiJson.encodeToString<ServerEvent>(event)}\n\n")
    }

    private companion object {
        val RECHECK = 5.seconds
        val LIVE_TICK = 250.milliseconds
        /** How often values that change all the time (round trips, byte counts) are sent. */
        val LIVE_REFRESH = 30.seconds
        val KEEPALIVE = 25.seconds
    }
}

/**
 * The snapshot without the values that change on every probe (round trips, byte counts, sample
 * times), to tell a state change from a new measurement.
 */
internal fun StatusSnapshot.withoutLiveValues(): StatusSnapshot = copy(
    generatedAt = 0,
    nodes = nodes.map { node ->
        node.copy(checks = node.checks.map { it.copy(value = null) })
    },
    services = services.map { service ->
        service.copy(perNode = service.perNode.map { it.copy(ms = null) })
    },
    tunnels = tunnels.map { tunnel ->
        tunnel.copy(
            bytesIn = null,
            bytesOut = null,
            updatedAt = null,
        )
    },
    mesh = mesh?.let { mesh ->
        mesh.copy(
            members = mesh.members.map { member ->
                member.copy(peers = member.peers.map { it.withoutLiveValues() })
            },
        )
    },
    cluster = cluster?.let { cluster ->
        cluster.copy(members = cluster.members.map { it.copy(lagBytes = null) })
    },
)

private fun MeshPeerDto.withoutLiveValues(): MeshPeerDto = copy(
    lastHandshakeAt = null,
    rxBytes = 0,
    txBytes = 0,
    probe = probe?.withoutLiveValues(),
    containerProbe = containerProbe?.withoutLiveValues(),
)

private fun MeshProbeDto.withoutLiveValues(): MeshProbeDto = copy(
    rttMs = null,
    lossPercent = 0.0,
    sent = 0,
    at = 0,
)

/** `GET /log`: the reconcile log. */
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class LogRoutes(private val controller: Controller) : RoutingController {
    override fun init(parent: Route) {
        parent.get("/log") { getLog() }
    }

    /** `?before=SEQ` pages back; `?domain=`, `?level=` and `?q=` filter. */
    suspend fun RoutingContext.getLog() {
        val parameters = call.request.queryParameters
        val entries = controller.log.recent(
            limit = (parameters["limit"]?.toIntOrNull() ?: 300).coerceIn(1, 1000),
            domain = parameters["domain"]?.takeIf { it.isNotBlank() },
            beforeSeq = parameters["before"]?.toLongOrNull(),
            level = parameters["level"]?.let { level -> LogLevel.entries.firstOrNull { it.name.equals(level, ignoreCase = true) } },
            query = parameters["q"],
        )
        call.respond(LogResponse(entries))
    }
}
