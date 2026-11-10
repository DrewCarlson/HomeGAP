package homegap.presentation.diagnostics

/** A terminal colour: one of the 16 theme colours, or an exact one (256-colour cube or 24-bit). */
sealed interface AnsiColor {
    /** 0-7 normal, 8-15 bright; drawn with the dashboard's own palette for the theme. */
    data class Indexed(val index: Int) : AnsiColor

    data class Rgb(val r: Int, val g: Int, val b: Int) : AnsiColor {
        val css: String get() = "rgb($r, $g, $b)"
    }
}

/** A run of text with the terminal style in effect for it. */
data class StyledRun(
    val text: String,
    val fg: AnsiColor? = null,
    val bg: AnsiColor? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
) {
    val plain: Boolean get() = fg == null && bg == null && !bold && !dim && !italic && !underline
}

/**
 * Container logs carry the colours the programs print for a terminal (ANSI escape sequences).
 * [parse] turns them into styled runs for the dashboard; [plain] drops them, for downloads. Only
 * colours and text styles (SGR) are kept: cursor moves, titles and other sequences mean nothing in
 * a page and are dropped, as are other control characters.
 */
object AnsiText {
    private const val ESC = '\u001B'

    fun plain(text: String): String = parse(text).joinToString("") { it.text }

    fun parse(text: String): List<StyledRun> {
        val runs = mutableListOf<StyledRun>()
        var style = StyledRun("")
        val buf = StringBuilder()
        fun flush() {
            if (buf.isEmpty()) return
            val run = style.copy(text = buf.toString())
            val last = runs.lastOrNull()
            if (last != null && last.copy(text = "") == style) runs[runs.size - 1] = last.copy(text = last.text + run.text) else runs += run
            buf.clear()
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == ESC && i + 1 < text.length && text[i + 1] == '[' -> {
                    // CSI: parameters and intermediates, then one final byte (@ to ~).
                    var j = i + 2
                    while (j < text.length && text[j] in ' '..'?') j++
                    if (j >= text.length) { i = text.length; continue }
                    if (text[j] == 'm') {
                        flush()
                        style = sgr(style, text.substring(i + 2, j))
                    }
                    i = j + 1
                }
                c == ESC && i + 1 < text.length && text[i + 1] == ']' -> {
                    // OSC (titles, links): up to BEL or ESC \.
                    var j = i + 2
                    while (j < text.length && text[j] != '\u0007' && !(text[j] == ESC && j + 1 < text.length && text[j + 1] == '\\')) j++
                    i = if (j < text.length && text[j] == ESC) j + 2 else j + 1
                }
                c == ESC -> i += 2 // any other escape: ESC and one character
                c == '\n' || c == '\t' -> { buf.append(c); i++ }
                c < ' ' || c == '\u007F' -> i++ // other control characters (\r, bells, ...)
                else -> { buf.append(c); i++ }
            }
        }
        flush()
        return runs
    }

    /** Applies one SGR sequence (the parameters between `ESC [` and `m`). */
    private fun sgr(from: StyledRun, params: String): StyledRun {
        val codes = if (params.isBlank()) listOf(0) else params.split(';', ':').map { it.toIntOrNull() ?: 0 }
        var s = from
        var k = 0
        while (k < codes.size) {
            when (val code = codes[k]) {
                0 -> s = StyledRun("")
                1 -> s = s.copy(bold = true)
                2 -> s = s.copy(dim = true)
                3 -> s = s.copy(italic = true)
                4 -> s = s.copy(underline = true)
                22 -> s = s.copy(bold = false, dim = false)
                23 -> s = s.copy(italic = false)
                24 -> s = s.copy(underline = false)
                in 30..37 -> s = s.copy(fg = AnsiColor.Indexed(code - 30))
                in 90..97 -> s = s.copy(fg = AnsiColor.Indexed(code - 90 + 8))
                39 -> s = s.copy(fg = null)
                in 40..47 -> s = s.copy(bg = AnsiColor.Indexed(code - 40))
                in 100..107 -> s = s.copy(bg = AnsiColor.Indexed(code - 100 + 8))
                49 -> s = s.copy(bg = null)
                38, 48 -> {
                    // 38;5;n or 38;2;r;g;b (and 48 for the background).
                    val (color, used) = extended(codes, k + 1)
                    s = if (code == 38) s.copy(fg = color) else s.copy(bg = color)
                    k += used
                }
            }
            k++
        }
        return s
    }

    private fun extended(codes: List<Int>, at: Int): Pair<AnsiColor?, Int> = when (codes.getOrNull(at)) {
        5 -> codes.getOrNull(at + 1)?.let { indexed(it) to 2 } ?: (null to 1)
        2 -> if (at + 3 < codes.size) AnsiColor.Rgb(codes[at + 1].coerceIn(0, 255), codes[at + 2].coerceIn(0, 255), codes[at + 3].coerceIn(0, 255)) to 4 else null to codes.size - at
        else -> null to 0
    }

    /** The xterm 256-colour palette: 16 theme colours, a 6x6x6 cube and a grey ramp. */
    private fun indexed(n: Int): AnsiColor? = when (n) {
        in 0..15 -> AnsiColor.Indexed(n)
        in 16..231 -> {
            val v = n - 16
            fun level(x: Int) = if (x == 0) 0 else 55 + x * 40
            AnsiColor.Rgb(level(v / 36), level(v / 6 % 6), level(v % 6))
        }
        in 232..255 -> (8 + (n - 232) * 10).let { AnsiColor.Rgb(it, it, it) }
        else -> null
    }
}
