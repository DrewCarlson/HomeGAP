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

    /**
     * Writes via a temp file + rename so readers never observe a partial file. The temp file is
     * created with [mode] (never wider, even for a moment) and synced to disk before the rename,
     * so a power loss leaves the old content or the new, not an empty file.
     */
    fun writeAtomic(path: String, content: String, mode: Int = "644".toInt(8)) =
        writeAtomicBytes(path, content.encodeToByteArray(), mode)

    /** [writeAtomic] for binary content. */
    fun writeAtomicBytes(path: String, bytes: ByteArray, mode: Int = "644".toInt(8)) {
        val parent = Path(path).parent
        if (parent != null) SystemFileSystem.createDirectories(parent)
        val tmp = "$path.tmp-${Random.nextInt(0, Int.MAX_VALUE)}"
        writeSynced(tmp, bytes, mode, O_WRONLY or O_CREAT or O_EXCL)
        try {
            SystemFileSystem.atomicMove(Path(tmp), Path(path))
            parent?.let { syncDirectory(it.toString()) }
        } catch (_: Exception) {
            // A single file bind-mounted into a container cannot be replaced by rename (EBUSY);
            // fall back to rewriting it in place.
            SystemFileSystem.delete(Path(tmp), mustExist = false)
            writeSynced(path, bytes, mode, O_WRONLY or O_CREAT or O_TRUNC)
        }
    }

    /** Opens [path] with [flags] and [mode], writes [bytes] and syncs them to disk. */
    private fun writeSynced(path: String, bytes: ByteArray, mode: Int, flags: Int) {
        val fd = open(path, flags, mode)
        check(fd >= 0) { "cannot write $path (errno $errno)" }
        try {
            // The umask may have narrowed the mode, and an existing file keeps its own.
            fchmod(fd, mode.convert())
            var done = 0
            bytes.usePinned { pinned ->
                while (done < bytes.size) {
                    val n = write(fd, pinned.addressOf(done), (bytes.size - done).convert())
                    check(n > 0) { "cannot write $path (errno $errno)" }
                    done += n.toInt()
                }
            }
            fsync(fd)
        } finally {
            close(fd)
        }
    }

    /** Syncs a directory, so a rename in it survives a power loss (best effort). */
    private fun syncDirectory(path: String) {
        val fd = open(path, O_RDONLY)
        if (fd < 0) return
        fsync(fd)
        close(fd)
    }

    /** Copies each regular file of [from] that [to] does not have yet (mode 0600); no-op when they are the same place. */
    fun copyMissing(from: String, to: String) {
        if (from.trimEnd('/') == to.trimEnd('/') || !isDirectory(from)) return
        for (name in list(from)) {
            val source = "$from/$name"
            val target = "$to/$name"
            if (isDirectory(source) || exists(target) || ".tmp-" in name) continue
            writeAtomic(target, readText(source), "600".toInt(8))
        }
    }

    fun chmodPath(path: String, mode: Int) {
        chmod(path, mode.convert())
    }

    /** [length] bytes of [path] from [offset] (fewer at the end of the file), read in place. */
    fun readRange(path: String, offset: Long, length: Int): ByteArray {
        val fd = open(path, O_RDONLY)
        check(fd >= 0) { "cannot open $path" }
        try {
            val out = ByteArray(length)
            var done = 0
            out.usePinned { pinned ->
                while (done < length) {
                    val n = pread(fd, pinned.addressOf(done), (length - done).convert(), (offset + done).convert())
                    if (n <= 0) break
                    done += n.toInt()
                }
            }
            return if (done == length) out else out.copyOf(done)
        } finally {
            close(fd)
        }
    }
}

