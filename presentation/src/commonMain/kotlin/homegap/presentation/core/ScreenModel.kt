package homegap.presentation.core

import homegap.presentation.ui.EmptyStateModel
import homegap.presentation.ui.InspectorModel
import homegap.presentation.ui.PageHeadModel

/**
 * Model of a top-level screen, chosen by the app presenter from the current route. [head] is the
 * top of the page, [inspector] the selected item (null when nothing is selected), [empty] what the
 * screen shows when it has nothing yet.
 */
interface ScreenModel {
    val head: PageHeadModel? get() = null
    val inspector: InspectorModel? get() = null
    val empty: EmptyStateModel? get() = null
}
