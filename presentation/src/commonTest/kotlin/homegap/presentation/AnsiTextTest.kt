package homegap.presentation

import homegap.presentation.diagnostics.AnsiColor
import homegap.presentation.diagnostics.AnsiText
import homegap.presentation.diagnostics.StyledRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnsiTextTest {
    private val esc = "\u001B"

    @Test
    fun aPangolinLogLineKeepsItsColour() {
        // As Pangolin prints it, inside `docker compose logs` output.
        val line = "pangolin  | 2026-11-10T11:09:39Z [$esc[32minfo$esc[39m]: Restored 2 client connections"
        val runs = AnsiText.parse(line)
        assertEquals(
            listOf(
                StyledRun("pangolin  | 2026-11-10T11:09:39Z ["),
                StyledRun("info", fg = AnsiColor.Indexed(2)),
                StyledRun("]: Restored 2 client connections"),
            ),
            runs,
        )
        assertEquals("pangolin  | 2026-11-10T11:09:39Z [info]: Restored 2 client connections", AnsiText.plain(line))
    }

    @Test
    fun stylesCombineAndReset() {
        val runs = AnsiText.parse("$esc[1;31mERR$esc[0m ok $esc[2;4;94mx$esc[22;24my$esc[m")
        assertEquals(StyledRun("ERR", fg = AnsiColor.Indexed(1), bold = true), runs[0])
        assertEquals(StyledRun(" ok "), runs[1])
        assertEquals(StyledRun("x", fg = AnsiColor.Indexed(12), dim = true, underline = true), runs[2])
        assertEquals(StyledRun("y", fg = AnsiColor.Indexed(12)), runs[3])
    }

    @Test
    fun extendedColours() {
        val runs = AnsiText.parse("$esc[38;5;196ma$esc[38;2;10;20;30;48;5;3mb$esc[38;5;244mc")
        assertEquals(AnsiColor.Rgb(255, 0, 0), runs[0].fg)
        assertEquals(AnsiColor.Rgb(10, 20, 30), runs[1].fg)
        assertEquals(AnsiColor.Indexed(3), runs[1].bg)
        assertEquals(AnsiColor.Rgb(128, 128, 128), runs[2].fg)
    }

    @Test
    fun otherSequencesAndControlsAreDropped() {
        // A cursor move, a window title, a bell and a carriage return carry no meaning in a page.
        val text = "a$esc[2Kb$esc]0;title\u0007c\u0007d\r\ne\tf$esc[31"
        assertEquals("abcd\ne\tf", AnsiText.plain(text))
        assertTrue(AnsiText.parse(text).all { it.plain })
    }

    @Test
    fun runsOfTheSameStyleMerge() {
        assertEquals(listOf(StyledRun("ab", fg = AnsiColor.Indexed(1))), AnsiText.parse("$esc[31ma$esc[31mb"))
        assertEquals(emptyList(), AnsiText.parse("$esc[31m$esc[0m"))
    }
}
