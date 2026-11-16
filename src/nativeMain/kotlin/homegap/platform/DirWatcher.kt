package homegap.platform

import kotlinx.coroutines.flow.Flow

/**
 * Emits when a process writes a file in [dir], moves a file into it or deletes a file from it,
 * if [accept] matches the name of the file. It also emits once when the watch starts, for a file
 * that is already there. Linux uses inotify (no polling). Other platforms (development hosts)
 * scan at an interval.
 */
expect fun watchDirectory(dir: String, accept: (fileName: String) -> Boolean = { true }): Flow<Unit>
