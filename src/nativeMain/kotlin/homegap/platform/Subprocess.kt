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
 * Child processes via ksubprocess. Waiting suspends (ksubprocess polls), and cancelling [run] kills
 * the child. [run] still moves to [Dispatchers.IO]: starting a child and writing its stdin block,
 * and a script larger than the pipe would otherwise hold up the controller's single event loop.
 */
object Subprocess {
    /**
     * Runs [argv] to completion, feeding [stdin], capturing output; killed after [timeout].
     * [env] is added to this process' environment (for the child only). A missing executable
     * throws.
     */
    suspend fun run(
        argv: List<String>,
        stdin: String = "",
        timeout: Duration = 600.seconds,
        env: Map<String, String> = emptyMap(),
    ): ProcessResult =
        withContext(Dispatchers.IO) {
            require(argv.isNotEmpty()) { "empty command" }
            val started = TimeMark.now()
            val p = Process {
                args(argv)
                environment.putAll(env)
                this.stdin = Redirect.Pipe
                stdout = Redirect.Pipe
                stderr = Redirect.Pipe
            }
            val r = p.communicate(input = stdin, timeout = timeout, killTimeout = 5.seconds)
            val timedOut = started.elapsed() >= timeout
            ProcessResult(r.exitCode, r.output, if (timedOut) r.errors + "\n[homegap] process timed out after $timeout" else r.errors)
        }

    /**
     * Starts a long-running child with stdin from /dev/null and stdout and stderr appended to
     * [logFile]. [env] is added to this process' environment. The caller must eventually wait for
     * it (see [Child]).
     */
    fun start(argv: List<String>, logFile: String, env: Map<String, String> = emptyMap()): Child {
        require(argv.isNotEmpty()) { "empty command" }
        // Pre-create the log so the child never creates it (and never picks its mode).
        if (!Files.exists(logFile)) Files.writeAtomic(logFile, "", "640".toInt(8))
        val p = Process {
            args(argv)
            environment.putAll(env)
            stdin = Redirect.Null
            stdout(logFile, append = true)
            stderr = Redirect.Stdout
        }
        return Child(p)
    }

/** A started child. Exactly one caller waits for it via [awaitExit]. */
    class Child(val process: Process) {
        /** Suspends until the child exits. */
        suspend fun awaitExit(): Int = process.waitFor()
        fun terminate() = runCatching { process.terminate() }
        fun kill() = runCatching { process.kill() }
    }
}

/** Monotonic elapsed-time helper. */
internal class TimeMark private constructor(private val mark: kotlin.time.TimeSource.Monotonic.ValueTimeMark) {
    fun elapsed(): Duration = mark.elapsedNow()
    companion object { fun now() = TimeMark(kotlin.time.TimeSource.Monotonic.markNow()) }
}
