package homegap

import homegap.platform.Files
import homegap.reconcile.ClusterReconciler
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClusterSecretsTest {
    private val http = HttpClient(CIO)

    @Test
    fun createsTheSecretsOnceAndKeepsThem() {
        val dir = tmpPath("cluster")
        val first = ClusterReconciler(dir, http).secrets()
        assertEquals(first, ClusterReconciler(dir, http).secrets())
        Files.delete("$dir/cluster/secrets.json")
    }

    @Test
    fun aFileWithoutTheControlPasswordsGetsThemAndKeepsTheOthers() {
        val dir = tmpPath("cluster")
        Files.mkdirs("$dir/cluster")
        Files.writeAtomic(
            "$dir/cluster/secrets.json",
            """{"postgres":"pg","replication":"repl","valkey":"vk","rest":"rest","pangolin":"pangolin","rewind":"rewind"}""",
            "600".toInt(8),
        )
        val secrets = ClusterReconciler(dir, http, inUse = { true }).secrets()
        assertEquals(listOf("pg", "repl", "vk", "rest", "pangolin", "rewind"), listOf(secrets.postgres, secrets.replication, secrets.valkey, secrets.rest, secrets.pangolin, secrets.rewind))
        assertEquals(32, secrets.etcd.length)
        assertEquals(32, secrets.sentinel.length)
        assertEquals(false, secrets.sentinel == secrets.valkey)
        // The new passwords are in the file, so a second start has the same.
        assertEquals(secrets, ClusterReconciler(dir, http, inUse = { true }).secrets())
        Files.delete("$dir/cluster/secrets.json")
    }

    @Test
    fun neverReplacesTheSecretsOfAClusterInUse() {
        val dir = tmpPath("cluster")
        // The file is gone, but the state shows a running cluster.
        assertFailsWith<IllegalStateException> { ClusterReconciler(dir, http, inUse = { true }).secrets() }
        // The file is there, but unreadable.
        Files.writeAtomic("$dir/cluster/secrets.json", "{ not json", "600".toInt(8))
        assertFailsWith<IllegalStateException> { ClusterReconciler(dir, http).secrets() }
        assertEquals("{ not json", Files.readText("$dir/cluster/secrets.json"))
        Files.delete("$dir/cluster/secrets.json")
    }
}
