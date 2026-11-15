package homegap.platform

import platform.posix.getegid
import platform.posix.geteuid
import platform.posix.getpwuid
import platform.posix.setenv

/**
 * The user of this process in the user list of the image. `ssh` stops if its user id has no entry
 * there, and the orchestrator runs as the user id of the owner of the stack folder, which the
 * image does not know. For such an id, the child processes get a user list with one more entry,
 * through `libnss_wrapper`.
 */
object Identity {
    private const val PASSWD = "/etc/passwd"
    private const val OWN_PASSWD = "/tmp/homegap-passwd"
    private const val NAME = "homegap-run"
    private const val OVERWRITE = 1

    /** Gives the user id of this process an entry for its child processes, if it has none. */
    fun ensureKnown(home: String) {
        val uid = geteuid()
        if (getpwuid(uid) != null) return
        val entry = "$NAME:x:$uid:${getegid()}::$home:/usr/sbin/nologin\n"
        Files.writeAtomic(OWN_PASSWD, Files.readTextOrNull(PASSWD).orEmpty() + entry)
        setenv("NSS_WRAPPER_PASSWD", OWN_PASSWD, OVERWRITE)
        setenv("NSS_WRAPPER_GROUP", "/etc/group", OVERWRITE)
        setenv("LD_PRELOAD", "libnss_wrapper.so", OVERWRITE)
    }
}
