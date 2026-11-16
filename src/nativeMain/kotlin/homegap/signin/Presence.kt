package homegap.signin

import homegap.platform.runCatchingCancellable
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * The control connections of each device, and the removal that waits for each device with none.
 *
 * A phone that sleeps ends its control connection and keeps its tunnel. The key of its tunnel
 * stays for [keep], so the tunnel works at once when the phone wakes. [remove] takes the key
 * away after that time.
 */
internal class Presence(
    private val scope: CoroutineScope,
    private val keep: Duration,
    private val remove: suspend (Device, String) -> Unit,
) {
    private val lock = SynchronizedObject()
    private val connections = mutableMapOf<String, Int>()
    private val removals = mutableMapOf<String, Job>()

    /** A control connection of the device [id] started. */
    fun arrive(id: String) {
        synchronized(lock) {
            connections[id] = (connections[id] ?: 0) + 1
            removals.remove(id)
        }?.cancel()
    }

    /** A control connection of the device [id] ended. Returns true if the device has no other one. */
    fun leave(id: String): Boolean = synchronized(lock) {
        val left = (connections[id] ?: 1) - 1
        if (left > 0) connections[id] = left else connections.remove(id)
        left <= 0
    }

    /**
     * Removes [key] of [device] after [keep], unless the device connects before that. [silent] is
     * how long the device has had no control connection already.
     */
    fun depart(device: Device, key: String, silent: Duration = Duration.ZERO) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(keep - silent)
            runCatchingCancellable { remove(device, key) }
        }
        val waits = synchronized(lock) {
            if (device.id in connections) return@synchronized false
            removals.put(device.id, job)?.cancel()
            true
        }
        if (waits) job.start() else job.cancel()
    }
}
