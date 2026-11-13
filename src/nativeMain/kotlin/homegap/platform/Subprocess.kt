package homegap.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import ksubprocess.Process
import ksubprocess.Redirect
import ksubprocess.communicate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * Child processes through ksubprocess. A wait suspends (ksubprocess polls), and a cancellation of
 * [run] kills the child. [run] still moves to [Dispatchers.IO], because the start of a child and
 * the write to its stdin block. On the single event loop of the controller, a script larger than
 * the pipe stops all other work.
 */
object Subprocess {
    private val KILL_TIMEOUT = 5.seconds

    /**
     * Runs [argv] to its end with [stdin] as input, and captures the output. Kills the child
     * after [timeout]. The child gets the environment of this process plus [env]. A missing
     * executable throws.
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
            val process = Process {
                args(argv)
                environment.putAll(env)
                this.stdin = Redirect.Pipe
                stdout = Redirect.Pipe
                stderr = Redirect.Pipe
            }
            val result = process.communicate(input = stdin, timeout = timeout, killTimeout = KILL_TIMEOUT)
            val timedOut = started.elapsed() >= timeout
            val errors = if (timedOut) {
                result.errors + "\n[homegap] process timed out after $timeout"
            } else {
                result.errors
            }
            ProcessResult(result.exitCode, result.output, errors)
        }

    /**
     * Starts a child that runs for a long time. Its stdin is /dev/null, and its stdout and stderr
     * go to the end of [logFile]. The child gets the environment of this process plus [env]. The
     * caller must wait for it (see [Child]).
     */
    fun start(argv: List<String>, logFile: String, env: Map<String, String> = emptyMap()): Child {
        require(argv.isNotEmpty()) { "empty command" }
        // Pre-create the log so the child never creates it (and never picks its mode).
        if (!Files.exists(logFile)) Files.writeAtomic(logFile, "", "640".toInt(8))
        val process = Process {
            args(argv)
            environment.putAll(env)
            stdin = Redirect.Null
            stdout(logFile, append = true)
            stderr = Redirect.Stdout
        }
        return Child(process)
    }

    /** A started child. Exactly one caller waits for it through [awaitExit]. */
    class Child(val process: Process) {
        /** Suspends until the child exits. */
        suspend fun awaitExit(): Int = process.waitFor()

        fun terminate() = runCatching { process.terminate() }

        fun kill() = runCatching { process.kill() }
    }
}

/** Monotonic elapsed-time helper. */
internal class TimeMark private constructor(private val mark: TimeSource.Monotonic.ValueTimeMark) {
    fun elapsed(): Duration = mark.elapsedNow()

    companion object {
        fun now() = TimeMark(TimeSource.Monotonic.markNow())
    }
}
