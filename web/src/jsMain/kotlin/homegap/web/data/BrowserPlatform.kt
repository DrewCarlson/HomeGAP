package homegap.web.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import homegap.presentation.data.PageVisibility
import homegap.presentation.data.Preferences
import homegap.presentation.data.TokenStore
import homegap.presentation.format.TimeFormatter
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.w3c.dom.Storage
import kotlin.js.Date
import kotlin.js.dateLocaleOptions

/** Keeps the dashboard token in localStorage. */
@ContributesBinding(AppScope::class)
@Inject
class LocalStorageTokenStore : TokenStore {
    override var token: String?
        get() = localStorage.getItem(KEY)
        set(value) = localStorage.put(KEY, value)

    private fun Storage.put(key: String, value: String?) {
        if (value.isNullOrBlank()) {
            removeItem(key)
        } else {
            setItem(key, value)
        }
    }

    private companion object {
        const val KEY = "homegap.token"
    }
}

/** Formats times in the browser's locale. */
@ContributesBinding(AppScope::class)
@Inject
class BrowserTimeFormatter : TimeFormatter {
    override fun time(epochMillis: Long): String =
        Date(epochMillis.toDouble()).toLocaleTimeString(options = dateLocaleOptions { hour = "2-digit"; minute = "2-digit" })
    override fun dateTime(epochMillis: Long): String = Date(epochMillis.toDouble()).toLocaleString()
}

/** Keeps per-browser settings in localStorage; a blocked storage just forgets them. */
@ContributesBinding(AppScope::class)
@Inject
class LocalStoragePreferences : Preferences {
    override fun get(key: String): String? = runCatching { localStorage.getItem(PREFIX + key) }.getOrNull()

    override fun set(key: String, value: String?) {
        runCatching {
            if (value == null) {
                localStorage.removeItem(PREFIX + key)
            } else {
                localStorage.setItem(PREFIX + key, value)
            }
        }
    }

    private companion object {
        const val PREFIX = "homegap."
    }
}

/** Follows `document.visibilityState`. */
@ContributesBinding(AppScope::class)
@Inject
class DocumentVisibility : PageVisibility {
    private val _visible = MutableStateFlow(document.asDynamic().visibilityState != "hidden")
    override val visible: StateFlow<Boolean> = _visible

    init {
        document.addEventListener("visibilitychange", {
            _visible.value = document.asDynamic().visibilityState != "hidden"
        })
    }
}
