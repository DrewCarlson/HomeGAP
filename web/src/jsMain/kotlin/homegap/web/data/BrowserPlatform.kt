package homegap.web.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import homegap.presentation.data.TokenStore
import homegap.presentation.format.TimeFormatter
import kotlinx.browser.localStorage
import kotlin.js.Date

/** Keeps the dashboard token in localStorage. */
@ContributesBinding(AppScope::class)
@Inject
class LocalStorageTokenStore : TokenStore {
    override var token: String?
        get() = localStorage.getItem(KEY)
        set(value) {
            if (value.isNullOrBlank()) {
                localStorage.removeItem(KEY)
            } else {
                localStorage.setItem(KEY, value)
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
    override fun time(epochMillis: Long): String = Date(epochMillis.toDouble()).toLocaleTimeString()
    override fun dateTime(epochMillis: Long): String = Date(epochMillis.toDouble()).toLocaleString()
}
