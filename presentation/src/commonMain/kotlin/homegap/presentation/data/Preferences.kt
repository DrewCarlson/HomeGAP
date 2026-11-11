package homegap.presentation.data

/** Small per-browser settings: the network view, search history (localStorage in the browser). */
interface Preferences {
    fun get(key: String): String?

    fun set(key: String, value: String?)
}
