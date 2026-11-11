package homegap.presentation.sharing

import androidx.compose.runtime.Immutable
import homegap.presentation.core.ScreenModel
import homegap.presentation.ui.PageHeadModel

/** The admin's list of read-only share links, and the form that makes a new one. */
sealed interface ShareLinksScreenModel : ScreenModel {
    data object Loading : ShareLinksScreenModel

    /** No dashboard token is set: everyone already has full control, so a link means nothing. */
    @Immutable
    data class Disabled(val message: String) : ShareLinksScreenModel

    @Immutable
    data class Ready(
        override val head: PageHeadModel,
        val label: String,
        val expiry: ShareExpiry,
        val expiries: List<ShareExpiry>,
        val canCreate: Boolean,
        val createText: String,
        val error: String?,
        /** The link just made, shown once to copy. */
        val created: CreatedLinkModel?,
        val links: List<ShareLinkRowModel>,
        val onEvent: (ShareLinksEvent) -> Unit,
    ) : ShareLinksScreenModel
}

/** How long a new link works. */
enum class ShareExpiry(val days: Int?, val label: String) {
    Day(1, "1 day"),
    Week(7, "7 days"),
    Month(30, "30 days"),
    Never(null, "Never"),
}

@Immutable
data class CreatedLinkModel(val label: String, val url: String)

@Immutable
data class ShareLinkRowModel(
    val id: String,
    val label: String,
    val created: String,
    val expires: String,
    val lastUsed: String,
)

sealed interface ShareLinksEvent {
    data class Label(val value: String) : ShareLinksEvent
    data class Expiry(val value: ShareExpiry) : ShareLinksEvent
    data object Create : ShareLinksEvent
    data class Revoke(val id: String) : ShareLinksEvent
    data object DismissCreated : ShareLinksEvent
}
