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
        Files.writeAtomic(
            "$dir/apt-get",
            "#!/bin/sh\nN=\$(( \$(cat $dir/n 2>/dev/null || echo 0) + 1 )); echo \$N > $dir/n\n$fake\n",
        )
        Subprocess.run(listOf("chmod", "+x", "$dir/apt-get"))
        val r = Subprocess.run(listOf("bash", "-c", "export PATH=$dir:\$PATH HG_APT_RETRY_DELAY=0\n${Apt.FUNCTION}\nhg_apt $args; echo \"rc=\$? calls=\$(cat $dir/n)\""))
        r.exitCode to (r.stdout + r.stderr)
    }

    @Test
    fun waitsOutTheListsLockOfAnotherApt() {
        // What apt-get update prints while cloud-init or another install holds the lists lock.
        val (_, out) = run(
            "[ \$N -le 2 ] && { echo 'E: Could not get lock /var/lib/apt/lists/lock. It is held by process 27087 (apt-get)' >&2; exit 100; }; exit 0",
        )
        assertTrue("rc=0 calls=3" in out, out)
    }

    @Test
    fun reportsOtherFailuresAtOnce() {
        val (_, out) = run("echo 'E: Unable to locate package nope' >&2; exit 100", "install -yq nope")
        assertTrue("rc=1 calls=1" in out, out)
        assertTrue("Unable to locate package nope" in out, out)
    }

    @Test
    fun isQuietWhenItWorks() {
        val (code, out) = run("echo 'Reading package lists...'; exit 0")
        assertEquals(0, code)
        assertEquals("rc=0 calls=1\n", out)
    }
}
