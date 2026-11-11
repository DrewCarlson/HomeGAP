package homegap.presentation.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether the dashboard's tab is visible; the browser's Page Visibility API in the web module. */
interface PageVisibility {
    val visible: StateFlow<Boolean>
}

/** Always visible: for tests and anywhere without a page. */
object AlwaysVisible : PageVisibility {
    override val visible: StateFlow<Boolean> = MutableStateFlow(true)
}
