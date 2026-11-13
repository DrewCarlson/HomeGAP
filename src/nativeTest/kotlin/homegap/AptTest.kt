package homegap

import homegap.platform.Files
import homegap.platform.Subprocess
import homegap.provision.Apt
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AptTest {
    /** Runs `hg_apt [args]` with a fake apt-get that runs [fake] (its call count in $N). */
    private fun run(fake: String, args: String = "update -q"): Pair<Int, String> = runBlocking {
        val dir = tmpPath("apt")
        Files.mkdirs(dir)
        val countCalls = $$"N=$(( $(cat $$dir/n 2>/dev/null || echo 0) + 1 )); echo $N > $$dir/n"
        Files.writeAtomic("$dir/apt-get", "#!/bin/sh\n$countCalls\n$fake\n")
        Subprocess.run(listOf("chmod", "+x", "$dir/apt-get"))
        val script = listOf(
            $$"export PATH=$$dir:$PATH HG_APT_RETRY_DELAY=0",
            Apt.FUNCTION,
            $$"hg_apt $$args; echo \"rc=$? calls=$(cat $$dir/n)\"",
        ).joinToString("\n")
        val result = Subprocess.run(listOf("bash", "-c", script))
        result.exitCode to (result.stdout + result.stderr)
    }

    @Test
    fun waitsOutTheListsLockOfAnotherApt() {
        // What apt-get update prints while cloud-init or another install holds the lists lock.
        val lockError = "E: Could not get lock /var/lib/apt/lists/lock. It is held by process 27087 (apt-get)"
        val (_, output) = run($$"[ $N -le 2 ] && { echo '$$lockError' >&2; exit 100; }; exit 0")
        assertTrue("rc=0 calls=3" in output, output)
    }

    @Test
    fun reportsOtherFailuresAtOnce() {
        val (_, output) = run("echo 'E: Unable to locate package nope' >&2; exit 100", "install -yq nope")
        assertTrue("rc=1 calls=1" in output, output)
        assertTrue("Unable to locate package nope" in output, output)
    }

    @Test
    fun isQuietWhenItWorks() {
        val (code, output) = run("echo 'Reading package lists...'; exit 0")
        assertEquals(0, code)
        assertEquals("rc=0 calls=1\n", output)
    }
}
