package homegap.stack

import homegap.platform.Files

/** What [StackFiles] did with one file. */
enum class FileResult { CREATED, UPDATED, UNCHANGED, REPLACED, KEPT }

data class FileOutcome(val path: String, val result: FileResult)

/**
 * Writes the files of the homelab stack to the folder of the user: `homegap init`.
 *
 * A **shipped file** is a file that the image has in [source] and that the user does not edit,
 * for example `compose.yml`. The folder [COPIES] in [target] holds a copy of each shipped file as
 * HomeGAP wrote it. If a file is equal to its copy, the user did not change it, and the file of a
 * new version replaces it with no question. If it is different, [confirm] decides.
 *
 * The folder [STARTERS] of [source] has the first content of the files of the user. It is not a
 * shipped file, and [target] gets no copy of it.
 */
class StackFiles(
    private val source: String,
    private val target: String,
    private val confirm: (path: String) -> Boolean,
) {
    private val written = mutableListOf<String>()

    /** Brings each shipped file in [target] to the version of [source]. */
    fun refresh(): List<FileOutcome> {
        check(Files.isDirectory(source)) { "the image has no stack files in $source" }
        val outcomes = shippedPaths("").map { FileOutcome(it, refreshFile(it)) }
        giveToOwner()
        return outcomes
    }

    /** Does [refresh], and then makes each file and folder that is the user's own, if it is missing. */
    fun init(): List<FileOutcome> {
        val outcomes = refresh().toMutableList()
        for ((path, template) in OWN_FILES) {
            if (Files.exists("$target/$path") || !Files.exists("$source/$template")) continue
            write(path, Files.readBytes("$source/$template"), OWN_MODES.getValue(path))
            outcomes += FileOutcome(path, FileResult.CREATED)
        }
        for ((path, mode) in OWN_FOLDERS) {
            if (Files.exists("$target/$path")) continue
            makeFolder(path)
            Files.chmodPath("$target/$path", mode)
        }
        giveToOwner()
        return outcomes
    }

    private fun refreshFile(path: String): FileResult {
        val fresh = Files.readBytes("$source/$path")
        val current = readOrNull("$target/$path")
        val copy = readOrNull("$target/$COPIES/$path")
        val result = when {
            current == null -> FileResult.CREATED
            current.contentEquals(fresh) -> FileResult.UNCHANGED
            copy != null && current.contentEquals(copy) -> FileResult.UPDATED
            confirm(path) -> FileResult.REPLACED
            else -> FileResult.KEPT
        }
        val mode = if (Files.isExecutable("$source/$path")) EXECUTABLE else PLAIN
        if (result == FileResult.KEPT) {
            write("$path$NEW_SUFFIX", fresh, mode)
            return result
        }
        Files.delete("$target/$path$NEW_SUFFIX")
        if (result == FileResult.REPLACED) write("$path$PREVIOUS_SUFFIX", checkNotNull(current), mode)
        if (result != FileResult.UNCHANGED) write(path, fresh, mode)
        if (copy == null || !copy.contentEquals(fresh)) write("$COPIES/$path", fresh, PLAIN)
        return result
    }

    /** The files below [folder] of [source], as paths relative to [source]. */
    private fun shippedPaths(folder: String): List<String> = Files.list("$source/$folder").flatMap { name ->
        val path = if (folder.isEmpty()) name else "$folder/$name"
        when {
            path == STARTERS -> emptyList()
            Files.isDirectory("$source/$path") -> shippedPaths(path)
            else -> listOf(path)
        }
    }

    private fun readOrNull(path: String): ByteArray? = if (Files.exists(path)) Files.readBytes(path) else null

    private fun write(path: String, bytes: ByteArray, mode: Int) {
        path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let(::makeFolder)
        Files.writeAtomicBytes("$target/$path", bytes, mode)
        written += path
    }

    /** Makes [path] below [target], one folder at a time, so that each new folder gets its owner. */
    private fun makeFolder(path: String) {
        val parts = path.split('/')
        for (depth in 1..parts.size) {
            val folder = parts.take(depth).joinToString("/")
            if (Files.exists("$target/$folder")) continue
            Files.mkdirs("$target/$folder")
            written += folder
        }
    }

    /**
     * Gives each new file to the owner of [target]. In a container, HomeGAP runs as root, and
     * without this step the user cannot edit the files.
     */
    private fun giveToOwner() {
        val owner = Files.owner(target)
        if (owner != null && Files.isRoot()) written.forEach { Files.chownPath("$target/$it", owner) }
        written.clear()
    }

    companion object {
        /** The folder in the target that holds the copies of the shipped files. */
        const val COPIES = ".homegap"
        const val NEW_SUFFIX = ".new"
        const val PREVIOUS_SUFFIX = ".previous"

        /** The folder in the source that holds the first content of the files of the user. */
        const val STARTERS = "starter"

        private val PLAIN = "644".toInt(8)
        private val EXECUTABLE = "755".toInt(8)
        private val FOLDER = "755".toInt(8)
        private val SECRET = "600".toInt(8)

        /** Each file of the user, with the file in the source that is its first content. */
        private val OWN_FILES = mapOf(".env" to ".env.example", "config/config.yml" to "$STARTERS/config.yml")
        private val OWN_MODES = mapOf(".env" to SECRET, "config/config.yml" to PLAIN)
        private val OWN_FOLDERS = mapOf("backups" to FOLDER)
    }
}
