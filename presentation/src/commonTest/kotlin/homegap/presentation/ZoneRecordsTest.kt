package homegap.presentation

import homegap.presentation.data.ActionRunner
import homegap.presentation.data.AlwaysVisible
import homegap.presentation.data.Confirmations
import homegap.presentation.data.ControllerSession
import homegap.presentation.routing.Location
import homegap.presentation.routing.Route
import homegap.presentation.zone.DnsZone
import homegap.presentation.zone.RecordDraft
import homegap.presentation.zone.RecordField
import homegap.presentation.zone.RecordInput
import homegap.presentation.zone.RecordKind
import homegap.presentation.zone.ZoneRecord
import homegap.presentation.zone.ZoneRecordsApi
import homegap.presentation.zone.ZoneRecordsScreenModel
import homegap.presentation.zone.ZoneRecordsScreenPresenter
import homegap.presentation.zone.ZoneRecordsScreenProps
import homegap.presentation.zone.ZoneSelection
import homegap.presentation.zone.recordProblems
import homegap.presentation.zone.underManagedWildcard
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZoneRecordsTest {
    private val net = listOf(
        ZoneRecord("m1", "@", "A", "203.0.113.11", RecordKind.Managed, ttlSeconds = 60, purpose = "front door", nodeId = "na1"),
        ZoneRecord("m2", "*", "A", "203.0.113.11", RecordKind.Managed, ttlSeconds = 60, purpose = "front door", nodeId = "na1"),
        ZoneRecord("c1", "_acme-challenge", "TXT", "token", RecordKind.Challenge, ttlSeconds = 60),
        ZoneRecord("o1", "mail", "A", "198.51.100.25", RecordKind.Own, ttlSeconds = 300),
        ZoneRecord("o2", "@", "MX", "mail.example.net", RecordKind.Own, ttlSeconds = 3600, priority = 10),
    )

    private class FakeZones(var zones: List<DnsZone>, override val canEdit: Boolean = true) : ZoneRecordsApi {
        val calls = mutableListOf<String>()
        var refuse: String? = null

        override suspend fun zones() = zones

        private fun change(call: String) {
            refuse?.let { throw IllegalStateException(it) }
            calls += call
        }

        override suspend fun add(zone: String, record: RecordDraft) = change("add $zone ${record.name} ${record.type} ${record.value} ${record.ttlSeconds}")

        override suspend fun update(zone: String, id: String, record: RecordDraft) = change("update $zone $id ${record.value} ${record.priority}")

        override suspend fun delete(zone: String, id: String) = change("delete $zone $id")
    }

    private class Screen(scope: TestScope, val api: FakeZones) {
        val opened = mutableListOf<Location>()
        val confirmations = Confirmations()
        private val controller = FakeControllerApi()
        val actions = ActionRunner(ControllerSession(controller, AlwaysVisible), controller, scope.backgroundScope)
        private val presenter = ZoneRecordsScreenPresenter(api, actions, confirmations)

        fun TestScope.at(selection: String?): StateFlow<ZoneRecordsScreenModel> = present(
            presenter,
            ZoneRecordsScreenProps(
                status = snapshot(),
                selection = selection,
                onOpen = { opened += it },
            ),
        )
    }

    private fun TestScope.screen(canEdit: Boolean = true) = Screen(
        scope = this,
        api = FakeZones(
            zones = listOf(DnsZone("example.net", net), DnsZone("example.org", net.take(1))),
            canEdit = canEdit,
        ),
    )

    @Test
    fun theListHasEveryRecordOfTheZoneAndMarksItsKind() = runTest {
        val model = with(screen()) { at(null) }.first { it.rows.isNotEmpty() }
        assertEquals("5 records in example.net.", model.head.headline)
        assertEquals("2 yours · 3 locked", model.counts)
        assertEquals(listOf("@", "@", "*", "_acme-challenge", "mail"), model.rows.map { it.name })
        assertEquals("10 mail.example.net", model.rows.first { it.type == "MX" }.value)
        assertEquals("front door · na1", model.rows.first().purpose)
        assertEquals(listOf("example.net", "example.org"), model.zones.map { it.name })
        assertNotNull(model.add)
    }

    @Test
    fun theSelectionPicksTheZoneAndALockedRecordHasNoEditActions() = runTest {
        val screen = screen()
        val other = with(screen) { at("example.org") }.first { it.rows.isNotEmpty() }
        assertEquals("1 record in example.org.", other.head.headline)
        assertTrue(other.noOwn)
        val locked = with(screen) { at("example.net/r/m2") }.first { it.inspector != null }.inspector!!
        assertEquals("*.example.net", locked.title)
        assertEquals(listOf("Open the record set"), locked.actions.map { it.label })
        val own = with(screen) { at("example.net/r/o1") }.first { it.inspector != null }.inspector!!
        assertEquals(listOf("Edit", "Delete"), own.actions.map { it.label })
    }

    @Test
    fun aUserWhoCanOnlyLookHasNoAddEditOrDelete() = runTest {
        val screen = screen(canEdit = false)
        val model = with(screen) { at("example.net/r/o1") }.first { it.inspector != null }
        assertNull(model.add)
        assertTrue(model.inspector!!.actions.isEmpty())
        assertNull(with(screen) { at("example.net/new") }.first { it.rows.isNotEmpty() }.form)
    }

    @Test
    fun theFormAddsARecordAfterItsFieldsAreRight() = runTest {
        val screen = screen()
        val models = with(screen) { at("example.net/new") }
        val form = models.first { it.form != null }.form!!
        form.onChange(RecordInput(type = "A", name = "www", value = "198.51.100.300"))
        models.first { it.form?.input?.name == "www" }.form!!.onSave()
        val failed = models.first { it.form?.errors?.isNotEmpty() == true }.form!!
        assertEquals("Enter an IPv4 address, for example 198.51.100.25.", failed.errors[RecordField.Value])
        assertEquals("www.example.net", failed.fullName)
        assertEquals("This name no longer goes to your nodes.", failed.nameNote)
        assertTrue(screen.api.calls.isEmpty())
        failed.onChange(failed.input.copy(value = "198.51.100.30"))
        models.first { it.form?.errors?.isEmpty() == true }.form!!.onSave()
        screen.actions.toasts.first { it.isNotEmpty() }
        assertEquals(listOf("add example.net www A 198.51.100.30 300"), screen.api.calls)
        assertEquals("Added www.example.net A.", screen.actions.toasts.value.last().text)
        assertEquals(Location(Route.ZoneRecords, "example.net"), screen.opened.last())
    }

    @Test
    fun aRefusalStaysInTheFormWithItsValues() = runTest {
        val screen = screen()
        screen.api.refuse = "Bunny refused the change: 503."
        val models = with(screen) { at("example.net/edit/o2") }
        val form = models.first { it.form != null }.form!!
        assertTrue(form.typeLocked)
        assertEquals("10", form.input.priority)
        form.onChange(form.input.copy(priority = "20"))
        models.first { it.form?.input?.priority == "20" }.form!!.onSave()
        val refused = models.first { it.form?.refusal != null }.form!!
        assertEquals("Bunny refused the change: 503.", refused.refusal)
        assertEquals("20", refused.input.priority)
        assertFalse(refused.saving)
        assertTrue(screen.opened.isEmpty())
    }

    @Test
    fun deleteAsksFirst() = runTest {
        val screen = screen()
        val inspector = with(screen) { at("example.net/r/o1") }.first { it.inspector != null }.inspector!!
        inspector.actions.last().onClick!!()
        val pending = screen.confirmations.pending.value!!
        assertEquals("mail.example.net A 198.51.100.25", pending.model.text)
        assertTrue(screen.api.calls.isEmpty())
        screen.confirmations.resolve(confirmed = true)
        screen.actions.toasts.first { it.isNotEmpty() }
        assertEquals(listOf("delete example.net o1"), screen.api.calls)
        assertEquals(Location(Route.ZoneRecords, "example.net"), screen.opened.last())
    }

    @Test
    fun theFormRefusesWhatTheServerRefuses() {
        fun problems(input: RecordInput) = recordProblems(
            input = input,
            zone = "example.net",
            records = net,
        )
        assertEquals("HomeGAP manages A records for example.net.", problems(RecordInput("A", "", "198.51.100.1"))[RecordField.Name])
        assertEquals(
            "HomeGAP manages records at example.net, so a CNAME cannot go there.",
            problems(RecordInput("CNAME", "@", "host.example.com"))[RecordField.Name],
        )
        assertTrue(problems(RecordInput("TXT", "@", "v=spf1 mx ~all")).isEmpty())
        assertTrue(problems(RecordInput("AAAA", "v6", "2001:db8::25")).isEmpty())
        assertNotNull(problems(RecordInput("AAAA", "v6", "2001:db8:::25"))[RecordField.Value])
        assertNotNull(problems(RecordInput("TXT", "bad name", "x"))[RecordField.Name])
        assertNotNull(problems(RecordInput("MX", "@", "mail.example.net", priority = "70000"))[RecordField.Priority])
        assertNotNull(problems(RecordInput("CAA", "@", "letsencrypt.org", flags = "1"))[RecordField.Flags])
        assertNotNull(problems(RecordInput("A", "mail", "198.51.100.25"))[RecordField.Name])
        assertTrue(underManagedWildcard("mail", net))
        assertFalse(underManagedWildcard("@", net))
    }

    @Test
    fun theSelectionKeepsARecordIdWithASlash() {
        listOf(
            ZoneSelection("example.net"),
            ZoneSelection("example.net", form = true),
            ZoneSelection("example.net", "a/b"),
            ZoneSelection("example.net", "a/b", form = true),
        ).forEach { assertEquals(it, ZoneSelection.parse(it.path)) }
        assertEquals(ZoneSelection(), ZoneSelection.parse(null))
    }
}
