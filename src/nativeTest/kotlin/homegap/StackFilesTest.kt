package homegap

import homegap.platform.Files
import homegap.stack.FileResult
import homegap.stack.StackFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StackFilesTest {
    private val source = tmpPath("stack-source")
    private val target = tmpPath("stack-target")
    private val asked = mutableListOf<String>()
    private var answer = false

    init {
        ship("compose.yml", "services: one\n")
        ship("upgrade.sh", "#!/bin/sh\n")
        Files.chmodPath("$source/upgrade.sh", "755".toInt(8))
        ship(".env.example", "BUNNY_API_KEY=\n")
        ship("config/config.example.yml", "domain: example.net\nproject: home-gap\n")
        ship("${StackFiles.STARTERS}/config.yml", "domain: example.net\n")
        Files.mkdirs(target)
    }

    private fun ship(path: String, content: String) = Files.writeAtomic("$source/$path", content)

    private fun stack() = StackFiles(source, target) { path ->
        asked += path
        answer
    }

    private fun text(path: String) = Files.readTextOrNull("$target/$path")

    @Test
    fun initMakesTheShippedFilesAndTheFilesOfTheUser() {
        val outcomes = stack().init().associate { it.path to it.result }

        val shipped = setOf("compose.yml", "upgrade.sh", ".env.example", "config/config.example.yml")
        val created = shipped + setOf(".env", "config/config.yml")
        assertEquals(created, outcomes.keys)
        assertTrue(outcomes.values.all { it == FileResult.CREATED }, outcomes.toString())
        assertEquals("services: one\n", text("compose.yml"))
        val (uid, gid) = Files.owner(target)!!
        val ownerLines = if (uid == 1000u && gid == 1000u) "" else "HOMEGAP_UID=$uid\nHOMEGAP_GID=$gid\n"
        assertTrue(text(".env")!!.startsWith("BUNNY_API_KEY=\n"))
        assertTrue(text(".env")!!.endsWith(ownerLines), "the orchestrator runs as the owner of the folder")
        assertEquals(ownerLines.isEmpty(), "HOMEGAP_UID" !in text(".env")!!)
        assertEquals("domain: example.net\n", text("config/config.yml"))
        assertTrue(Files.isExecutable("$target/upgrade.sh"))
        assertFalse(Files.isExecutable("$target/compose.yml"))
        val folders = listOf("data/orchestrator", "data/mesh", "data/identity", "data/sign-in", "data/vpn")
        assertTrue(folders.all { Files.isDirectory("$target/$it") }, "the folders of the orchestrator")
        assertFalse(Files.exists("$target/${StackFiles.STARTERS}"), "the starter files stay in the image")
        assertTrue(asked.isEmpty())
    }

    @Test
    fun aSecondInitLeavesTheFilesOfTheUserAlone() {
        stack().init()
        Files.writeAtomic("$target/.env", "BUNNY_API_KEY=secret\n")
        Files.writeAtomic("$target/config/config.yml", "domain: mine.example\n")
        ship(".env.example", "BUNNY_API_KEY=\nNEW=\n")

        val outcomes = stack().init().associate { it.path to it.result }

        assertEquals("BUNNY_API_KEY=secret\n", text(".env"))
        assertEquals("domain: mine.example\n", text("config/config.yml"))
        assertEquals(FileResult.UPDATED, outcomes[".env.example"])
        assertEquals(FileResult.UNCHANGED, outcomes["compose.yml"])
        assertFalse(".env" in outcomes)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun aShippedFileWithNoChangeGetsTheNewVersionWithNoQuestion() {
        stack().init()
        ship("compose.yml", "services: two\n")

        val outcomes = stack().refresh().associate { it.path to it.result }

        assertEquals(FileResult.UPDATED, outcomes["compose.yml"])
        assertEquals("services: two\n", text("compose.yml"))
        assertEquals("services: two\n", text("${StackFiles.COPIES}/compose.yml"))
        assertTrue(asked.isEmpty())
        assertFalse(".env" in outcomes, "a refresh makes no file of the user")
    }

    @Test
    fun aShippedFileThatTheUserChangedStaysAfterANo() {
        stack().init()
        Files.writeAtomic("$target/compose.yml", "services: mine\n")
        ship("compose.yml", "services: two\n")

        val outcomes = stack().refresh().associate { it.path to it.result }

        assertEquals(listOf("compose.yml"), asked)
        assertEquals(FileResult.KEPT, outcomes["compose.yml"])
        assertEquals("services: mine\n", text("compose.yml"))
        assertEquals("services: two\n", text("compose.yml${StackFiles.NEW_SUFFIX}"))

        answer = true
        val second = stack().refresh().associate { it.path to it.result }

        assertEquals(FileResult.REPLACED, second["compose.yml"])
        assertEquals("services: two\n", text("compose.yml"))
        assertEquals("services: mine\n", text("compose.yml${StackFiles.PREVIOUS_SUFFIX}"))
        assertFalse(Files.exists("$target/compose.yml${StackFiles.NEW_SUFFIX}"))
        assertEquals(setOf(FileResult.UNCHANGED), stack().refresh().map { it.result }.toSet())
    }

    @Test
    fun aFileFromBeforeInitIsAskedAbout() {
        Files.writeAtomic("$target/compose.yml", "services: old\n")

        val outcomes = stack().init().associate { it.path to it.result }

        assertEquals(listOf("compose.yml"), asked)
        assertEquals(FileResult.KEPT, outcomes["compose.yml"])
        assertEquals("services: old\n", text("compose.yml"))
    }
}
