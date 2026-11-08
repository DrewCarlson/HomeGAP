package homegap.presentation.core

import androidx.compose.runtime.Composable

/**
 * Turns [PropsT] (inputs from the parent: route arguments, callbacks) into a [ModelT] that the UI
 * renders as-is. Presenters hold state and logic in Compose (remember, produceState, effects);
 * views hold none.
 */
interface Presenter<in PropsT : Any, out ModelT : Any?> {
    @Composable
    fun model(props: PropsT): ModelT
}
