@file:JsModule("@xterm/xterm")

package homegap.web.terminal

import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/** The terminal emulator of xterm.js: only what the dashboard uses. */
external class Terminal(options: TerminalOptions = definedExternally) {
    val rows: Int
    val cols: Int
    val options: TerminalOptions
    val modes: TerminalModes

    fun open(parent: HTMLElement)

    /** Draws [data]; [callback] runs once it is on the screen. */
    fun write(data: Uint8Array, callback: () -> Unit = definedExternally)
    fun reset()
    fun focus()
    fun hasSelection(): Boolean
    fun loadAddon(addon: TerminalAddon)

    /** [handler] returns false for a key that the terminal must leave to the browser. */
    fun attachCustomKeyEventHandler(handler: (KeyboardEvent) -> Boolean)

    /** What the user typed or pasted, as text. */
    fun onData(listener: (String) -> Unit)

    /** Input that is not text (some mouse reports), one byte per character. */
    fun onBinary(listener: (String) -> Unit)
    fun onResize(listener: (TerminalSize) -> Unit)
    fun dispose()
}

external interface TerminalOptions {
    var fontFamily: String
    var fontSize: Int
    var cursorBlink: Boolean
    var scrollback: Int
    var theme: TerminalTheme
}

external interface TerminalTheme {
    var background: String
    var foreground: String
    var cursor: String
    var cursorAccent: String
    var selectionBackground: String
    var black: String
    var red: String
    var green: String
    var yellow: String
    var blue: String
    var magenta: String
    var cyan: String
    var white: String
    var brightBlack: String
    var brightRed: String
    var brightGreen: String
    var brightYellow: String
    var brightBlue: String
    var brightMagenta: String
    var brightCyan: String
    var brightWhite: String
}

external interface TerminalSize {
    val rows: Int
    val cols: Int
}

external interface TerminalModes {
    /** The program in the shell asked for the other arrow key codes (vim, less). */
    val applicationCursorKeysMode: Boolean
}

external interface TerminalAddon
