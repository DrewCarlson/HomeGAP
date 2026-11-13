package homegap.web.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import homegap.api.ApiJson
import homegap.api.TerminalMessage
import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.w3c.dom.ARRAYBUFFER
import org.w3c.dom.BinaryType
import org.w3c.dom.CloseEvent
import org.w3c.dom.HTMLElement
import org.w3c.dom.WebSocket

/**
 * One terminal of the panel: the xterm.js emulator and the WebSocket to its session on the
 * orchestrator. Binary frames carry the terminal bytes both ways; text frames carry a
 * [TerminalMessage]. The orchestrator stops reading the shell when the browser falls behind, so
 * every chunk is acknowledged once it is drawn.
 */
class TerminalConnection(
    private val onConnected: () -> Unit,
    private val onDisconnected: (reason: String) -> Unit,
    private val onExited: (code: Int) -> Unit,
) {
    private val terminal = Terminal(terminalOptions())
    private val fitAddon = FitAddon()
    private val searchAddon = SearchAddon()
    private var socket: WebSocket? = null
    private var host: HTMLElement? = null
    private var resizeObserver: dynamic = null

    /** The position, in the output of the session, of the next byte to arrive. */
    private var offset = 0L

    /** The phone key row's Ctrl: the next key goes with it. */
    var ctrlArmed by mutableStateOf(false)

    /** The latest focus request of the panel that this terminal has answered. */
    var focusAnswered = 0

    init {
        terminal.loadAddon(fitAddon)
        terminal.loadAddon(searchAddon)
        terminal.loadAddon(WebLinksAddon())
        terminal.onData { type(it) }
        terminal.onBinary { data -> send(ByteArray(data.length) { data[it].code.toByte() }) }
        terminal.onResize { size -> send(TerminalMessage.Resize(rows = size.rows, columns = size.cols)) }
        terminal.attachCustomKeyEventHandler { event ->
            val key = event.key.lowercase()
            val copy = key == "c" && terminal.hasSelection()
            // Copy (with a selection) and paste stay the browser's; the terminal gets every other key.
            !(event.type == "keydown" && (event.ctrlKey || event.metaKey) && (copy || key == "v"))
        }
    }

    /** Shows the terminal in [host] and keeps it the size of [host]. */
    fun mount(host: HTMLElement) {
        this.host = host
        terminal.open(host)
        fit()
        val onResize = ::fit
        resizeObserver = js("new ResizeObserver(function () { onResize(); })")
        resizeObserver.observe(host)
    }

    /** Opens the socket with [ticket]. The session sends its recent output again, so the screen starts empty. */
    fun connect(ticket: String) {
        closeSocket()
        terminal.reset()
        val scheme = if (window.location.protocol == "https:") "wss" else "ws"
        val opened = WebSocket("$scheme://${window.location.host}/api/terminal/ws?ticket=$ticket")
        opened.binaryType = BinaryType.ARRAYBUFFER
        socket = opened
        opened.onmessage = { event ->
            if (socket === opened) {
                receive(event.data)
            }
        }
        opened.onclose = { event ->
            if (socket === opened) {
                socket = null
                onDisconnected((event as CloseEvent).reason)
            }
        }
    }

    /** Types [text] into the shell, with Ctrl when the key row armed it. */
    fun type(text: String) {
        val input = if (ctrlArmed) withCtrl(text) else text
        ctrlArmed = false
        send(input.encodeToByteArray())
    }

    /** Types [command] and presses Enter. */
    fun run(command: String) {
        type("$command\r")
        terminal.focus()
    }

    /** An arrow key: [letter] is A (up), B (down), C (right) or D (left). */
    fun arrow(letter: Char) {
        type(if (terminal.modes.applicationCursorKeysMode) "\u001bO$letter" else "\u001b[$letter")
    }

    fun find(text: String, backwards: Boolean = false) {
        if (backwards) {
            searchAddon.findPrevious(text)
        } else {
            searchAddon.findNext(text)
        }
    }

    fun setFontSize(pixels: Int) {
        terminal.options.fontSize = pixels
        fit()
    }

    fun focus() {
        terminal.focus()
    }

    fun dispose() {
        closeSocket()
        resizeObserver?.disconnect()
        terminal.dispose()
    }

    /** A minimized panel has no size: the terminal and its shell keep the size they had. */
    private fun fit() {
        val shown = host?.let { it.clientWidth > 0 && it.clientHeight > 0 } == true
        if (shown) {
            fitAddon.fit()
        }
    }

    private fun receive(data: Any?) {
        if (data is String) {
            when (val message = ApiJson.decodeFromString(TerminalMessage.serializer(), data)) {
                is TerminalMessage.Hello -> {
                    offset = message.offset
                    send(TerminalMessage.Resize(rows = terminal.rows, columns = terminal.cols))
                    onConnected()
                }

                is TerminalMessage.Exit -> onExited(message.code)
                else -> Unit
            }
        } else {
            val bytes = Uint8Array(data.unsafeCast<ArrayBuffer>())
            val drawn = offset + bytes.length
            offset = drawn
            terminal.write(bytes) { send(TerminalMessage.Ack(drawn)) }
        }
    }

    private fun send(message: TerminalMessage) {
        socket?.takeIf { it.readyState == WebSocket.OPEN }?.send(ApiJson.encodeToString(TerminalMessage.serializer(), message))
    }

    private fun send(bytes: ByteArray) {
        socket?.takeIf { it.readyState == WebSocket.OPEN }?.send(bytes.unsafeCast<Int8Array>())
    }

    /** Closes the socket without a report: the caller replaces it or leaves. */
    private fun closeSocket() {
        val open = socket
        socket = null
        open?.close()
    }
}

/** [text] as Ctrl sends it: one letter or one of `@ [ \ ] ^ _` becomes its control code. */
private fun withCtrl(text: String): String {
    val char = text.singleOrNull()?.uppercaseChar() ?: return text
    return if (char in '@'..'_') (char.code and 0x1f).toChar().toString() else text
}

/** The dashboard's font and palette, read from the variables of styles.css. */
private fun terminalOptions(): TerminalOptions {
    val styles = window.getComputedStyle(document.documentElement!!)
    val color = { name: String -> styles.getPropertyValue("--$name").trim() }
    val theme = js("({})").unsafeCast<TerminalTheme>().apply {
        background = color("ground")
        foreground = color("ink")
        cursor = color("cyan-soft")
        cursorAccent = color("ground")
        selectionBackground = "rgba(142, 246, 255, .28)"
        black = color("rule-strong")
        red = color("rose")
        green = color("ansi-2")
        yellow = color("amber")
        blue = color("ansi-4")
        magenta = color("violet")
        cyan = color("cyan")
        white = color("ink")
        brightBlack = color("ink-faint")
        brightRed = color("ansi-9")
        brightGreen = color("ansi-10")
        brightYellow = color("ansi-11")
        brightBlue = color("ansi-12")
        brightMagenta = color("ansi-13")
        brightCyan = color("cyan-soft")
        brightWhite = "#FFFFFF"
    }
    return js("({})").unsafeCast<TerminalOptions>().apply {
        fontFamily = styles.getPropertyValue("--mono").trim()
        cursorBlink = true
        scrollback = SCROLLBACK_LINES
        this.theme = theme
    }
}

private const val SCROLLBACK_LINES = 5000
