package homegap.platform

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Development hosts only: a plain periodic scan. */
actual fun watchDirectory(dir: String, accept: (fileName: String) -> Boolean): Flow<Unit> = flow {
    while (true) { delay(3000); emit(Unit) }
}
