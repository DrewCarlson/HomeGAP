package homegap

import homegap.dashboard.DocsFiles
import homegap.platform.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DocsFilesTest {
    @Test
    fun pagesResolveInsideTheDocsDirectoryOnly() {
        val dir = tmpPath("docs")
        Files.writeAtomic("$dir/index.html", "home")
        Files.writeAtomic("$dir/cluster/index.html", "cluster")
        Files.writeAtomic("$dir/assets/app.js", "js")
        assertEquals("$dir/index.html", DocsFiles.resolve("/docs/", dir))
        assertEquals("$dir/cluster/index.html", DocsFiles.resolve("/docs/cluster/", dir))
        assertEquals("$dir/cluster/index.html", DocsFiles.resolve("/docs/cluster", dir), "the caller adds the slash")
        assertEquals("$dir/assets/app.js", DocsFiles.resolve("/docs/assets/app.js", dir))
        assertNull(DocsFiles.resolve("/docs/missing/", dir))
        assertNull(DocsFiles.resolve("/docs/../etc/passwd", dir))
        assertNull(DocsFiles.resolve("/docs/%2e%2e/etc/passwd", dir))
    }
}
