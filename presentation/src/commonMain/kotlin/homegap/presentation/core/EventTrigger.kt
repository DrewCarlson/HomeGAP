package homegap.presentation.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A stable handle that runs a suspending [handler] in [scope]; models expose [trigger] as an event. */
class EventTrigger(
    private val scope: CoroutineScope,
    private val handler: suspend () -> Unit,
) {
    fun trigger() {
        scope.launch { handler() }
    }
}

@Composable
fun CoroutineScope.rememberEventTrigger(handler: suspend () -> Unit): EventTrigger =
    remember { EventTrigger(this, handler) }
