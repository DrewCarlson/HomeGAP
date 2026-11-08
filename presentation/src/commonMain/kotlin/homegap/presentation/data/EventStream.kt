package homegap.presentation.data

/** One server-sent event. */
data class ServerEvent(val name: String, val data: String)

/**
 * Incremental parser for a `text/event-stream` body: feed it decoded chunks as they arrive and
 * it returns the events that are complete so far (only `event:` and `data:` fields are used).
 */
class EventStreamParser {
    private var buffer = ""

    fun feed(chunk: String): List<ServerEvent> {
        buffer += chunk
        return buildList {
            while (true) {
                val end = buffer.indexOf("\n\n")
                if (end < 0) break
                val block = buffer.substring(0, end)
                buffer = buffer.substring(end + 2)
                parseBlock(block)?.let(::add)
            }
        }
    }

    private fun parseBlock(block: String): ServerEvent? {
        var name = "message"
        val data = mutableListOf<String>()
        for (line in block.lineSequence()) {
            when {
                line.startsWith("event:") -> name = line.removePrefix("event:").trim()
                line.startsWith("data:") -> data += line.removePrefix("data:").trimStart()
            }
        }
        // Comment-only blocks (": keepalive") carry no data.
        return if (data.isEmpty()) null else ServerEvent(name, data.joinToString("\n"))
    }
}
