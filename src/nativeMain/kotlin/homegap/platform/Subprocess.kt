package homegap.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ksubprocess.Process
import ksubprocess.Redirect
import ksubprocess.communicate
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * Child processes via ksubprocess. Several ksubprocess (0.9.0) behaviours shape this wrapper:
 * - `waitFor()` blocks its thread in `waitid`, so it always runs on [Dispatchers.IO].
 * - A child is only reaped when its exit is observed, so every process started here is waited for.
 * - `communicate()` reads stdout/stderr but never closes those streams, and a pipe's descriptor is
 *   only released once every stream opened on it is closed: [run] closes them, or each call would
 *   leak two descriptors until new processes fail with "Error opening stdin/stdout pipe".
 * - `Redirect.Stdout` (stderr merged into stdout) closes the shared descriptor twice in the parent,
 *   which can close an unrelated descriptor that reused the number; [start] never merges.
 */
object Subprocess {
    /**
     * Runs [argv] to completion, feeding [stdin], capturing output; killed after [timeout].
     * [env] is added to this process' environment (for the child only).
     */
    suspend fun run(
        argv: List<String>,
        stdin: String = "",
        timeout: Duration = 600.seconds,
        env: Map<String, String> = emptyMap(),
    ): ProcessResult =
        withContext(Dispatchers.IO) {
            val started = TimeMark.now()
            val p = Process {
                args(resolve(argv))
                environment.putAll(env)
                this.stdin = Redirect.Pipe
                stdout = Redirect.Pipe
                stderr = Redirect.Pipe
            }
            try {
                val r = p.communicate(input = stdin, timeout = timeout, killTimeout = 5.seconds)
                val timedOut = started.elapsed() >= timeout
                ProcessResult(r.exitCode, r.output, if (timedOut) r.errors + "\n[homegap] process timed out after $timeout" else r.errors)
            } finally {
                withContext(NonCancellable) { release(p) }
            }
        }

    /** Reaps [p] if `communicate` did not get that far, then releases its pipe descriptors. */
    @OptIn(ExperimentalNativeApi::class)
    private suspend fun release(p: Process) {
        if (p.exitCode == null) {
            runCatching { p.kill() }
            p.waitFor()
        }
        // The leak is in the Linux backend. The macOS backend (NSTask) frees its handles on GC and
        // can abort the process if a stream its reader thread still uses is closed here.
        if (Platform.osFamily == OsFamily.LINUX) {
            runCatching { p.stdout?.close() }
            runCatching { p.stderr?.close() }
        }
    }

    /**
     * Starts a long-running child with stdin from /dev/null and stdout and stderr both appended to
     * [logFile] (opened separately, see above). [env] is added to this process' environment. The
     * caller must eventually wait for it (see [Child]).
     */
    fun start(argv: List<String>, logFile: String, env: Map<String, String> = emptyMap()): Child {
        // Pre-create the log so the child never creates it (and never picks its mode).
        if (!Files.exists(logFile)) Files.writeAtomic(logFile, "", "640".toInt(8))
        val p = Process {
            args(resolve(argv))
            environment.putAll(env)
            stdin = Redirect.Null
            stdout(logFile, append = true)
            stderr(logFile, append = true)
        }
        return Child(p)
    }

    /**
     * Resolves argv[0] against PATH. The macOS backend (NSTask) requires an absolute path and
     * aborts the process otherwise, so a missing binary must fail here as a normal exception.
     */
    private fun resolve(argv: List<String>): List<String> {
        require(argv.isNotEmpty()) { "empty command" }
        val exe = argv[0]
        if ('/' in exe) {
            if (!Files.exists(exe)) throw IllegalStateException("executable not found: $exe")
            return argv
        }
        val path = env("PATH") ?: "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        val found = path.split(':').map { "$it/$exe" }.firstOrNull { platform.posix.access(it, platform.posix.X_OK) == 0 }
            ?: throw IllegalStateException("executable not found on PATH: $exe")
        return listOf(found) + argv.drop(1)
    }

    /** A started child. Exactly one caller waits for it via [awaitExit]. */
    class Child(val process: Process) {
        /** Suspends until the child exits (blocking an IO thread in `waitid`, no polling). */
        suspend fun awaitExit(): Int = withContext(Dispatchers.IO) { process.waitFor() }
        fun terminate() = runCatching { process.terminate() }
        fun kill() = runCatching { process.kill() }
    }
}

/** Monotonic elapsed-time helper. */
internal class TimeMark private constructor(private val mark: kotlin.time.TimeSource.Monotonic.ValueTimeMark) {
    fun elapsed(): Duration = mark.elapsedNow()
    companion object { fun now() = TimeMark(kotlin.time.TimeSource.Monotonic.markNow()) }
}
