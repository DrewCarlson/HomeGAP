package homegap.presentation.data

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.timeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The longest silence on `/api/events` before the dashboard treats the stream as dropped. The
 * server writes an event or a keepalive comment at least every 25 seconds, so a longer gap means
 * that the connection stalled without closing.
 */
val EVENT_STREAM_SILENCE_LIMIT: Duration = 60.seconds

/** Ends the flow when nothing arrives for [limit], the same way a closed connection ends it. */
@OptIn(FlowPreview::class)
fun <T> Flow<T>.endAfterSilence(limit: Duration): Flow<T> =
    timeout(limit).catch { error ->
        if (error !is TimeoutCancellationException) {
            throw error
        }
    }
