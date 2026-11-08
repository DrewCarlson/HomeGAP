package homegap.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import ksubprocess.Process
import ksubprocess.Redirect
import ksubprocess.communicate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * Child processes via ksubprocess. Two ksubprocess behaviours shape this wrapper:
 * `waitFor()` blocks its thread in `waitid` (so it always runs on [Dispatchers.IO]), and a child
 * is only reaped when its exit is observed (so every process started here is waited for).
 */
object Subprocess {
    /** Runs [argv] to completion, feeding [stdin], capturing output; killed after [timeout]. */
    suspend fun run(argv: List<String>, stdin: String = "", timeout: Duration = 600.seconds): ProcessResult =
        withContext(Dispatchers.IO) {
            val started = TimeMark.now()
            val p = Process {
                args(resolve(argv))
                this.stdin = Redirect.Pipe
                stdout = Redirect.Pipe
                stderr = Redirect.Pipe
            }
            val r = p.communicate(input = stdin, timeout = timeout, killTimeout = 5.seconds)
            val timedOut = started.elapsed() >= timeout
            ProcessResult(r.exitCode, r.output, if (timedOut) r.errors + "\n[homegap] process timed out after $timeout" else r.errors)
        }

    /**
     * Starts a long-running child with stdin from /dev/null and stdout+stderr appended to
     * [logFile]. [env] is added to this process' environment. The caller must eventually call
     * [Process.waitFor] (see [Child]).
     */
    fun start(argv: List<String>, logFile: String, env: Map<String, String> = emptyMap()): Child {
        // Pre-create the log so the child never creates it (and never picks its mode).
        if (!Files.exists(logFile)) Files.writeAtomic(logFile, "", "640".toInt(8))
        val p = Process {
            args(resolve(argv))
            environment.putAll(env)
            stdin = Redirect.Null
            stdout(logFile, append = true)
            stderr = Redirect.Stdout
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
