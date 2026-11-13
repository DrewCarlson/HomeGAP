package homegap.platform

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.seconds

private val SCAN_INTERVAL = 3.seconds

/** Development hosts only: a scan at a fixed interval. */
actual fun watchDirectory(dir: String, accept: (fileName: String) -> Boolean): Flow<Unit> = flow {
    while (true) {
        delay(SCAN_INTERVAL)
        emit(Unit)
    }
}
