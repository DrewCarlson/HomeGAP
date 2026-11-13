package homegap.provision

import kotlinx.serialization.json.*

/**
 * Renders a JSON tree as block-style YAML. Each string has double quotes and JSON escapes, which
 * is a valid subset of YAML. Thus a person can read the rendered file, and a value cannot inject
 * YAML.
 */
object YamlEmitter {
    private val PLAIN_KEY = Regex("^[A-Za-z0-9_][A-Za-z0-9_./-]*$")
    private const val INDENT = 2

    fun emit(root: JsonElement, header: String? = null): String = buildString {
        header?.lines()?.forEach { appendLine("# $it") }
        when (root) {
            is JsonObject -> emitObject(root, 0)
            is JsonArray -> emitArray(root, 0)
            else -> appendLine(scalar(root))
        }
    }

    private fun key(name: String) = if (PLAIN_KEY.matches(name)) name else JsonPrimitive(name).toString()

    private fun scalar(element: JsonElement): String = when (element) {
        is JsonNull -> "null"
        is JsonPrimitive -> if (element.isString) element.toString() else element.content
        is JsonObject -> if (element.isEmpty()) "{}" else error("not scalar")
        is JsonArray -> if (element.isEmpty()) "[]" else error("not scalar")
    }

    private fun isScalar(element: JsonElement) = element is JsonPrimitive ||
        element is JsonNull ||
        (element is JsonObject && element.isEmpty()) ||
        (element is JsonArray && element.isEmpty())

    private fun StringBuilder.emitObject(obj: JsonObject, indent: Int) {
        val pad = " ".repeat(indent)
        for ((name, value) in obj) {
            if (isScalar(value)) {
                appendLine("$pad${key(name)}: ${scalar(value)}")
            } else {
                appendLine("$pad${key(name)}:")
                when (value) {
                    is JsonObject -> emitObject(value, indent + INDENT)
                    is JsonArray -> emitArray(value, indent + INDENT)
                    else -> Unit
                }
            }
        }
    }

    private fun StringBuilder.emitArray(array: JsonArray, indent: Int) {
        val pad = " ".repeat(indent)
        for (item in array) {
            when {
                isScalar(item) -> appendLine("$pad- ${scalar(item)}")
                item is JsonObject -> {
                    // The first key goes on the line with the dash. The other keys go below it, in line.
                    val inner = StringBuilder()
                        .apply { emitObject(item, indent + INDENT) }
                        .toString()
                        .lines()
                        .filter { it.isNotEmpty() }
                    appendLine("$pad- " + inner.first().drop(indent + INDENT))
                    inner.drop(1).forEach { appendLine(it) }
                }
                item is JsonArray -> {
                    appendLine("$pad-")
                    emitArray(item, indent + INDENT)
                }
            }
        }
    }
}
