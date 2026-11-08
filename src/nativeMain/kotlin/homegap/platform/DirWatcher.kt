package homegap.platform

import kotlinx.coroutines.flow.Flow

/**
 * Emits whenever files in [dir] matching [accept] are written, renamed into it or deleted. Linux uses inotify
 * (no polling); other platforms (development hosts) fall back to a periodic scan.
 */
expect fun watchDirectory(dir: String, accept: (fileName: String) -> Boolean = { true }): Flow<Unit>
