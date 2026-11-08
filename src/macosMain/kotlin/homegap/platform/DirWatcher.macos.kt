package homegap.platform

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.seconds

/** Development hosts only: a plain periodic scan. */
actual fun watchDirectory(dir: String, accept: (fileName: String) -> Boolean): Flow<Unit> = flow {
    while (true) { delay(3.seconds); emit(Unit) }
}
