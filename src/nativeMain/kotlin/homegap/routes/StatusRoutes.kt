package homegap.routes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import homegap.api.LogResponse
import homegap.api.ServerEvent
import homegap.api.name
import homegap.api.StatusSnapshot
import homegap.platform.nowMillis
import homegap.reconcile.Controller
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
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/** The status snapshot, the reconcile log, and the event stream that pushes both. */
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class StatusRoutes(private val controller: Controller) : RoutingController {
    private val json = Json { encodeDefaults = true }

    override fun init(parent: Route) {
        parent.get("/status") { getStatus() }
        parent.get("/events") { streamEvents() }
        parent.get("/log") { getLog() }
    }

    suspend fun RoutingContext.getStatus() {
        call.respond(controller.snapshot())
    }

    suspend fun RoutingContext.getLog() {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 300
        call.respond(LogResponse(controller.log.recent(limit, call.request.queryParameters["domain"])))
    }

    /**
     * Server-sent events: `status` (the snapshot, only when it changed) and `log` (entries after
     * `?after=SEQ`). Tunnel status comes from files the supervisor writes, so the snapshot is
     * also re-checked every few seconds even without a change signal.
     */
    suspend fun RoutingContext.streamEvents() {
        var lastSeq = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L
        // A client that saw a previous controller process has a sequence ahead of ours.
        if (lastSeq > controller.log.revision.value) lastSeq = 0L
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header("X-Accel-Buffering", "no")
        call.respondBytesWriter(ContentType.Text.EventStream) { writeEvents(lastSeq) }
    }

    private suspend fun ByteWriteChannel.writeEvents(afterSeq: Long) = coroutineScope {
        var lastSeq = afterSeq
        var lastStatus: StatusSnapshot? = null
        var lastWrite = 0L
        val changes = controller.changes.conflate().produceIn(this)
        try {
            while (true) {
                val snapshot = controller.snapshot()
                val comparable = snapshot.copy(generatedAt = 0)
                if (comparable != lastStatus) {
                    writeEvent(ServerEvent.Status(snapshot))
                    lastStatus = comparable
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
                withTimeoutOrNull(RECHECK) { changes.receive() }
            }
        } finally {
            changes.cancel()
        }
    }

    private suspend fun ByteWriteChannel.writeEvent(event: ServerEvent) {
        writeStringUtf8("event: ${event.name}\ndata: ${json.encodeToString<ServerEvent>(event)}\n\n")
    }

    private companion object {
        val RECHECK = 5.seconds
        val KEEPALIVE = 25.seconds
    }
}
