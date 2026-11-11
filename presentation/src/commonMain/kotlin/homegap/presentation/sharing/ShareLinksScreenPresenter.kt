package homegap.presentation.sharing

import androidx.compose.runtime.*
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.CreateShareLinkRequest
import homegap.api.ShareLinkDto
import homegap.api.ShareLinksResponse
import homegap.presentation.access.expiresIn
import homegap.presentation.core.Presenter
import homegap.presentation.core.rememberEventTrigger
import homegap.presentation.core.runCatchingCancellable
import homegap.presentation.data.ActionRunner
import homegap.presentation.data.AppCoroutineScope
import homegap.presentation.data.Confirmations
import homegap.presentation.data.ControllerApi
import homegap.presentation.data.PageAddress
import homegap.presentation.data.shareUrl
import homegap.presentation.format.ago
import homegap.presentation.format.plural
import homegap.presentation.ui.ConfirmModel
import homegap.presentation.ui.PageHeadModel
import kotlinx.coroutines.CoroutineScope

data class ShareLinksScreenProps(val now: Long)

/** Lists, creates and revokes read-only share links. Only an admin reaches it. */
@SingleIn(AppScope::class)
@Inject
class ShareLinksScreenPresenter(
    private val api: ControllerApi,
    private val actions: ActionRunner,
    private val confirmations: Confirmations,
    private val address: PageAddress,
    @AppCoroutineScope private val appScope: CoroutineScope,
) : Presenter<ShareLinksScreenProps, ShareLinksScreenModel> {
    @Composable
    override fun model(props: ShareLinksScreenProps): ShareLinksScreenModel {
        var reload by remember { mutableIntStateOf(0) }
        val response by produceState<ShareLinksResponse?>(null, reload) {
            runCatchingCancellable { api.shareLinks() }
                .onSuccess { value = it }
                .onFailure { actions.notify("Could not load share links: ${it.message}", isError = true) }
        }
        var label by remember { mutableStateOf("") }
        var expiry by remember { mutableStateOf(ShareExpiry.Week) }
        var creating by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var created by remember { mutableStateOf<CreatedLinkModel?>(null) }
        val request by rememberUpdatedState(CreateShareLinkRequest(label.trim(), expiry.days))

        val create = appScope.rememberEventTrigger {
            creating = true
            error = null
            try {
                runCatchingCancellable { api.createShareLink(request) }
                    .onSuccess { result ->
                        created = CreatedLinkModel(
                            label = result.link.label,
                            url = shareUrl(address.origin, result.token),
                        )
                        label = ""
                        reload++
                    }
                    .onFailure { error = it.message }
            } finally {
                creating = false
            }
        }

        val current = response ?: return ShareLinksScreenModel.Loading
        if (!current.enabled) {
            return ShareLinksScreenModel.Disabled(
                "Share links need a dashboard token. Without one, anyone who can open the dashboard already has " +
                    "full control. Set dashboard.auth_token_env and restart the orchestrator.",
            )
        }
        return ShareLinksScreenModel.Ready(
            head = PageHeadModel(
                caption = "Access · Share links",
                headline = if (current.links.isEmpty()) "No share links." else "${plural(current.links.size, "share link")} open.",
            ),
            label = label,
            expiry = expiry,
            expiries = remember { ShareExpiry.entries },
            canCreate = label.isNotBlank() && !creating,
            createText = if (creating) "Creating…" else "Create link",
            error = error,
            created = created,
            links = current.links.map { it.row(props.now) },
            onEvent = { event ->
                when (event) {
                    is ShareLinksEvent.Label -> {
                        label = event.value
                        error = null
                    }

                    is ShareLinksEvent.Expiry -> expiry = event.value
                    ShareLinksEvent.Create -> create.trigger()
                    ShareLinksEvent.DismissCreated -> created = null
                    is ShareLinksEvent.Revoke -> current.links.firstOrNull { it.id == event.id }?.let { link ->
                        confirmations.ask(
                            ConfirmModel(
                                title = "Revoke “${link.label}”?",
                                text = "The link stops working at once, also in a browser that has it open.",
                                confirmLabel = "Revoke",
                                danger = true,
                            )
                        ) {
                            actions.run("Revoke", onSuccess = { reload++ }) { api.revokeShareLink(link.id) }
                        }
                    }
                }
            },
        )
    }

    private fun ShareLinkDto.row(now: Long) = ShareLinkRowModel(
        id = id,
        label = label,
        created = ago(createdAt, now),
        expires = expiresAt?.let { expiresIn(it, now) } ?: "never",
        lastUsed = lastUsedAt?.let { ago(it, now) } ?: "not yet",
    )
}
