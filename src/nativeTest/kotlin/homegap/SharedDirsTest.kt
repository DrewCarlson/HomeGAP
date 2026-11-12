package homegap

import homegap.config.SharedDirs
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedDirsTest {
    private val mounts = """
        2114 1893 0:233 / / rw,relatime master:702 - overlay overlay rw
        2115 2114 0:236 / /proc rw,nosuid - proc proc rw
        2130 2114 254:1 /docker/volumes/homegap-data/_data /data rw,relatime - ext4 /dev/vda1 rw
        2131 2114 254:1 /docker/volumes/homegap-mesh/_data /mesh rw,relatime - ext4 /dev/vda1 rw
    """.trimIndent()

    @Test
    fun aVolumeIsItsOwnMountPoint() {
        assertEquals("/mesh", SharedDirs.mountPoint("/mesh", mounts))
        assertEquals("/data", SharedDirs.mountPoint("/data/mesh", mounts))
    }

    @Test
    fun aDirectoryOfTheImageIsOnTheRootFileSystem() {
        assertEquals("/", SharedDirs.mountPoint("/tunnels", mounts))
        // A name that only starts like a mount point is not inside it.
        assertEquals("/", SharedDirs.mountPoint("/mesh-old", mounts))
    }
}
