package homegap.provision

import kotlinx.serialization.json.*

/**
 * Renders a JSON tree as block-style YAML. Strings are always double-quoted using JSON escapes
 * (a valid YAML subset), so rendered files are both human-readable and injection-safe.
 */
object YamlEmitter {
    private val PLAIN_KEY = Regex("^[A-Za-z0-9_][A-Za-z0-9_./-]*$")

    fun emit(root: JsonElement, header: String? = null): String = buildString {
        header?.lines()?.forEach { appendLine("# $it") }
        when (root) {
            is JsonObject -> emitObject(root, 0)
            is JsonArray -> emitArray(root, 0)
            else -> appendLine(scalar(root))
        }
    }

    private fun key(k: String) = if (PLAIN_KEY.matches(k)) k else JsonPrimitive(k).toString()

    private fun scalar(e: JsonElement): String = when (e) {
        is JsonNull -> "null"
        is JsonPrimitive -> if (e.isString) e.toString() else e.content
        is JsonObject -> if (e.isEmpty()) "{}" else error("not scalar")
        is JsonArray -> if (e.isEmpty()) "[]" else error("not scalar")
    }

    private fun isScalar(e: JsonElement) = e is JsonPrimitive || e is JsonNull ||
        (e is JsonObject && e.isEmpty()) || (e is JsonArray && e.isEmpty())

    private fun StringBuilder.emitObject(o: JsonObject, indent: Int) {
        val pad = " ".repeat(indent)
        for ((k, v) in o) {
            if (isScalar(v)) {
                appendLine("$pad${key(k)}: ${scalar(v)}")
            } else {
                appendLine("$pad${key(k)}:")
                when (v) {
                    is JsonObject -> emitObject(v, indent + 2)
                    is JsonArray -> emitArray(v, indent + 2)
                    else -> Unit
                }
            }
        }
    }

    private fun StringBuilder.emitArray(a: JsonArray, indent: Int) {
        val pad = " ".repeat(indent)
        for (v in a) {
            when {
                isScalar(v) -> appendLine("$pad- ${scalar(v)}")
                v is JsonObject -> {
                    // first key on the dash line, remaining keys aligned beneath it
                    val inner = StringBuilder().apply { emitObject(v, indent + 2) }.toString().lines().filter { it.isNotEmpty() }
                    appendLine("$pad- " + inner.first().drop(indent + 2))
                    inner.drop(1).forEach { appendLine(it) }
                }
                v is JsonArray -> {
                    appendLine("$pad-")
                    emitArray(v, indent + 2)
                }
            }
        }
    }
}
