package homegap.presentation.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Current time in epoch milliseconds, refreshed every [period] so relative labels ("5m ago")
 * stay current without the views doing any time arithmetic.
 */
@Composable
fun Clock.rememberNow(period: Duration = 5.seconds): State<Long> =
    produceState(now().toEpochMilliseconds(), this, period) {
        while (true) {
            delay(period)
            value = now().toEpochMilliseconds()
        }
    }
