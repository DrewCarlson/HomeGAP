@file:JsModule("@xterm/addon-search")

package homegap.web.terminal

/** Finds text in the scrollback and selects it. */
external class SearchAddon : TerminalAddon {
    fun findNext(term: String): Boolean
    fun findPrevious(term: String): Boolean
}
