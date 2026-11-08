package homegap.platform

import kotlinx.cinterop.*
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString
import platform.posix.*
import kotlin.random.Random
import kotlin.time.Clock

fun env(name: String): String? = getenv(name)?.toKString()

fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

object Files {
    fun exists(path: String): Boolean = SystemFileSystem.exists(Path(path))

    fun isDirectory(path: String): Boolean = SystemFileSystem.metadataOrNull(Path(path))?.isDirectory == true

    fun readText(path: String): String =
        SystemFileSystem.source(Path(path)).buffered().use { it.readString() }

    fun readTextOrNull(path: String): String? = if (exists(path)) runCatching { readText(path) }.getOrNull() else null

    fun readBytes(path: String): ByteArray =
        SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() }

    fun size(path: String): Long = SystemFileSystem.metadataOrNull(Path(path))?.size ?: 0L

    /** Up to [max] bytes of the file starting at [offset], as text ("" if missing). */
    fun readFrom(path: String, offset: Long, max: Int = 65536): String = runCatching {
        val n = (size(path) - offset).coerceIn(0, max.toLong()).toInt()
        if (n == 0) return@runCatching ""
        SystemFileSystem.source(Path(path)).buffered().use { src ->
            src.skip(offset)
            src.readByteArray(n).decodeToString()
        }
    }.getOrDefault("")

    fun mkdirs(path: String) {
        SystemFileSystem.createDirectories(Path(path))
    }

    fun list(path: String): List<String> =
        if (!isDirectory(path)) emptyList() else SystemFileSystem.list(Path(path)).map { it.name }.sorted()

    fun delete(path: String) {
        SystemFileSystem.delete(Path(path), mustExist = false)
    }

    /** Writes via a temp file + rename so readers never observe a partial file. */
    fun writeAtomic(path: String, content: String, mode: Int = "644".toInt(8)) {
        val parent = Path(path).parent
        if (parent != null) SystemFileSystem.createDirectories(parent)
        val tmp = "$path.tmp-${Random.nextInt(0, Int.MAX_VALUE)}"
        SystemFileSystem.sink(Path(tmp)).buffered().use { it.writeString(content) }
        chmod(tmp, mode.convert())
        try {
            SystemFileSystem.atomicMove(Path(tmp), Path(path))
        } catch (_: Exception) {
            // A single file bind-mounted into a container cannot be replaced by rename (EBUSY);
            // fall back to rewriting it in place.
            SystemFileSystem.delete(Path(tmp), mustExist = false)
            SystemFileSystem.sink(Path(path)).buffered().use { it.writeString(content) }
        }
    }

    fun chmodPath(path: String, mode: Int) {
        chmod(path, mode.convert())
    }
}

