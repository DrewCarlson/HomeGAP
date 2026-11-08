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
        } catch (e: Exception) {
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

/** Result of running a child process to completion. */
data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * Minimal POSIX process runner: fork/exec with stdin/stdout/stderr pipes.
 * Blocking; callers run it on Dispatchers.IO.
 */
object Processes {
    fun run(
        argv: List<String>,
        stdin: ByteArray? = null,
        env: Map<String, String> = emptyMap(),
        timeoutSeconds: Int = 600,
    ): ProcessResult = memScoped {
        require(argv.isNotEmpty())
        val inPipe = allocArray<IntVar>(2)
        val outPipe = allocArray<IntVar>(2)
        val errPipe = allocArray<IntVar>(2)
        check(pipe(inPipe) == 0 && pipe(outPipe) == 0 && pipe(errPipe) == 0) { "pipe() failed" }

        val cArgv = allocArray<CPointerVar<ByteVar>>(argv.size + 1)
        argv.forEachIndexed { i, a -> cArgv[i] = a.cstr.ptr }
        cArgv[argv.size] = null

        val envList = buildEnv(env)
        val cEnv = allocArray<CPointerVar<ByteVar>>(envList.size + 1)
        envList.forEachIndexed { i, a -> cEnv[i] = a.cstr.ptr }
        cEnv[envList.size] = null

        val exe = resolveExecutable(argv[0]).cstr.ptr
        val pid = fork()
        if (pid == 0) {
            // child: only async-signal-safe libc calls with pre-allocated buffers past this point
            dup2(inPipe[0], 0); dup2(outPipe[1], 1); dup2(errPipe[1], 2)
            close(inPipe[0]); close(inPipe[1]); close(outPipe[0]); close(outPipe[1]); close(errPipe[0]); close(errPipe[1])
            execve(exe, cArgv, cEnv)
            _exit(127)
        }
        check(pid > 0) { "fork() failed" }
        close(inPipe[0]); close(outPipe[1]); close(errPipe[1])

        // Feed stdin fully, then close. Payloads are small (scripts/config) so this cannot deadlock
        // against the child filling its stdout pipe in practice; stdout is drained right after.
        if (stdin != null && stdin.isNotEmpty()) {
            stdin.usePinned { pinned ->
                var off = 0
                while (off < stdin.size) {
                    val n = write(inPipe[1], pinned.addressOf(off), (stdin.size - off).convert())
                    if (n <= 0) break
                    off += n.toInt()
                }
            }
        }
        close(inPipe[1])

        val out = StringBuilder()
        val err = StringBuilder()
        drain(outPipe[0], errPipe[0], out, err, timeoutSeconds, pid)
        close(outPipe[0]); close(errPipe[0])

        val status = alloc<IntVar>()
        waitpid(pid, status.ptr, 0)
        val s = status.value
        val code = if ((s and 0x7f) == 0) (s shr 8) and 0xff else 128 + (s and 0x7f)
        ProcessResult(code, out.toString(), err.toString())
    }

    private fun buildEnv(extra: Map<String, String>): List<String> {
        val base = mutableMapOf<String, String>()
        for (k in listOf("PATH", "HOME", "USER", "LANG", "TMPDIR", "SSH_AUTH_SOCK")) env(k)?.let { base[k] = it }
        if ("PATH" !in base) base["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        base.putAll(extra)
        return base.map { (k, v) -> "$k=$v" }
    }

    private fun MemScope.drain(outFd: Int, errFd: Int, out: StringBuilder, err: StringBuilder, timeoutSeconds: Int, pid: Int) {
        val fds = allocArray<pollfd>(2)
        fds[0].fd = outFd; fds[0].events = POLLIN.convert()
        fds[1].fd = errFd; fds[1].events = POLLIN.convert()
        val buf = allocArray<ByteVar>(16384)
        var open = 2
        val deadline = nowMillis() + timeoutSeconds * 1000L
        val outBytes = mutableListOf<Byte>()
        val errBytes = mutableListOf<Byte>()
        while (open > 0) {
            if (nowMillis() > deadline) {
                kill(pid, SIGKILL)
                err.append("\n[homegap] process timed out after ${timeoutSeconds}s")
                break
            }
            val r = poll(fds, 2u, 1000)
            if (r < 0) { if (errno == EINTR) continue else break }
            if (r == 0) continue
            for (i in 0..1) {
                val re = fds[i].revents.toInt()
                if (fds[i].fd < 0 || re == 0) continue
                val n = read(fds[i].fd, buf, 16384u)
                if (n <= 0) {
                    fds[i].fd = -1
                    open--
                } else {
                    val target = if (i == 0) outBytes else errBytes
                    for (j in 0 until n.toInt()) target.add(buf[j])
                }
            }
        }
        out.append(outBytes.toByteArray().decodeToString())
        err.append(errBytes.toByteArray().decodeToString())
    }

    /** The full environment of this process (Linux: /proc/self/environ; elsewhere a small whitelist). */
    fun inheritedEnv(): Map<String, String> {
        val raw = runCatching { Files.readBytes("/proc/self/environ") }.getOrNull()
        if (raw == null || raw.isEmpty()) {
            return listOf("PATH", "HOME", "USER", "LANG", "TZ", "SSL_CERT_FILE", "SSL_CERT_DIR", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY")
                .mapNotNull { k -> env(k)?.let { k to it } }.toMap()
        }
        return raw.decodeToString().split('\u0000').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    /**
     * Spawns a long-running child with stdout/stderr redirected to [logFile]; returns its pid.
     * With [inherit] the child gets this process' full environment plus [env].
     */
    fun spawnDetached(argv: List<String>, env: Map<String, String>, logFile: String, inherit: Boolean = false): Int = memScoped {
        val cArgv = allocArray<CPointerVar<ByteVar>>(argv.size + 1)
        argv.forEachIndexed { i, a -> cArgv[i] = a.cstr.ptr }
        cArgv[argv.size] = null
        val envList = if (inherit) (inheritedEnv() + env).map { (k, v) -> "$k=$v" } else buildEnv(env)
        val cEnv = allocArray<CPointerVar<ByteVar>>(envList.size + 1)
        envList.forEachIndexed { i, a -> cEnv[i] = a.cstr.ptr }
        cEnv[envList.size] = null
        val logPath = logFile.cstr.ptr
        val devNull = "/dev/null".cstr.ptr
        val exe = resolveExecutable(argv[0]).cstr.ptr
        val logMode = "640".toInt(8)
        val pid = fork()
        if (pid == 0) {
            setsid()
            val fd = open(logPath, O_WRONLY or O_CREAT or O_APPEND, logMode)
            if (fd >= 0) { dup2(fd, 1); dup2(fd, 2); close(fd) }
            val dn = open(devNull, O_RDONLY)
            if (dn >= 0) { dup2(dn, 0); close(dn) }
            execve(exe, cArgv, cEnv)
            _exit(127)
        }
        check(pid > 0) { "fork() failed" }
        pid
    }

    /** Non-blocking reap: returns the exit description if [pid] has exited, else null. */
    fun reap(pid: Int): String? = memScoped {
        val status = alloc<IntVar>()
        val r = waitpid(pid, status.ptr, WNOHANG)
        if (r == 0) return null
        if (r < 0) return "gone"
        val s = status.value
        if ((s and 0x7f) == 0) "exit ${(s shr 8) and 0xff}" else "signal ${s and 0x7f}"
    }

    fun terminate(pid: Int) {
        kill(pid, SIGTERM)
    }

    fun forceKill(pid: Int) {
        kill(pid, SIGKILL)
    }
}

/** Resolves [file] against PATH in the parent so the forked child needs no allocation. */
fun resolveExecutable(file: String): String {
    if (file.contains('/')) return file
    val path = env("PATH") ?: "/usr/local/bin:/usr/bin:/bin"
    for (dir in path.split(':')) {
        val candidate = "$dir/$file"
        if (access(candidate, X_OK) == 0) return candidate
    }
    return file
}
