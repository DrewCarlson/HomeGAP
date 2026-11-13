package homegap.presentation

import homegap.presentation.data.AlwaysVisible
import homegap.api.CheckDto
import homegap.api.Health
import homegap.api.PlaceDto
import homegap.presentation.addnode.AddNodeScreenPresenter
import homegap.presentation.addnode.AddNodeScreenProps
import homegap.presentation.addnode.AddNodeWizardStore
import homegap.presentation.addnode.WizardEvent
import homegap.presentation.addnode.WizardField
import homegap.presentation.data.ActionRunner
import homegap.presentation.data.ControllerSession
import homegap.presentation.locations.LocationField
import homegap.presentation.locations.LocationSearch
import homegap.presentation.locations.LocationsEvent
import homegap.presentation.locations.LocationsScreenPresenter
import homegap.presentation.locations.LocationsScreenProps
import homegap.presentation.map.MarkerKind
import homegap.presentation.map.overviewMap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MapsAndLocationsTest {
    private val dallas = PlaceDto(32.78, -96.8, "Dallas, Texas")

    @Test
    fun theOverviewMapDrawsNodesTheHomelabAndItsTunnels() {
        val down = node("b1", Health.DOWN).copy(
            location = PlaceDto(19.08, 72.88, "Mumbai"),
            checks = listOf(CheckDto("pangolin", Health.DOWN, "running (unhealthy)"), CheckDto("ssh", Health.OK)),
        )
        val estimated = node("a1").copy(region = "EU", location = PlaceDto(50.1, 8.7, null, estimated = true), tunnelUp = true)
        val status = snapshot(nodes = listOf(estimated, down)).copy(homelab = dallas)
        val map = status.overviewMap(NOW)

        val home = map.markers.single { it.kind == MarkerKind.Homelab }
        assertEquals(dallas.latitude, home.latitude)
        val b1 = map.markers.single { it.id == "b1" }
        assertEquals(Health.DOWN, b1.health)
        assertEquals("Mumbai · AS", b1.info?.subtitle)
        assertTrue(b1.info!!.rows.any { it.label == "pangolin" && it.health == Health.DOWN }, "what's failing")
        assertTrue(b1.info!!.rows.none { it.label == "ssh" }, "healthy checks stay out")
        assertEquals("EU (approximate)", map.markers.single { it.id == "a1" }.info?.subtitle)
        assertEquals(mapOf("home-a1" to Health.OK, "home-b1" to Health.DOWN), map.lines.associate { it.id to it.health }, "lines follow the tunnels")

        val noHome = snapshot(nodes = listOf(down)).overviewMap(NOW)
        assertTrue(noHome.lines.isEmpty() && noHome.markers.none { it.kind == MarkerKind.Homelab })
    }

    @Test
    fun searchingAndNamingLocations() = runTest {
        val all = FakeControllerApi().locationsValue.locations
        assertEquals(listOf("us-dallas"), LocationSearch.search(all, "dal").map { it.id })
        assertEquals(listOf("in-mumbai", "sg-singapore"), LocationSearch.search(all, "asia vultr").map { it.id }.sorted(), "every word must match")
        assertTrue(LocationSearch.search(all, "  ").isEmpty())
        assertEquals("in-mumbai", LocationSearch.nearest(all, 19.2, 72.9)?.id, "a click near Mumbai names it")
        assertNull(LocationSearch.nearest(all, 0.0, 0.0), "nothing within 50 km")
        assertEquals("EU", LocationSearch.regionFor(48.86, 2.35))
        assertEquals("Mumbai, India", LocationSearch.label(all.single { it.id == "in-mumbai" }))
        assertEquals("Singapore", LocationSearch.label(all.single { it.id == "sg-singapore" }))
    }

    @Test
    fun aClosedWizardLeavesNothingForTheNextOneButAReloadKeepsTheDraft() {
        val preferences = FakePreferences()
        val first = AddNodeWizardStore(preferences)
        first.update { it.copy(values = it.values + (WizardField.Host to "203.0.113.9")) }
        assertEquals("203.0.113.9", AddNodeWizardStore(preferences).draft.value[WizardField.Host], "a reload of the open wizard keeps the draft")
        first.close()
        first.update { it.copy(values = it.values + (WizardField.NodeId to "late")) }
        assertEquals(homegap.presentation.addnode.AddNodeDraft(), AddNodeWizardStore(preferences).draft.value, "the next wizard starts empty")
    }

    @Test
    fun pickingALocationFillsTheWizard() = runTest {
        val api = FakeControllerApi()
        val store = AddNodeWizardStore(FakePreferences())
        val actions = ActionRunner(ControllerSession(api, AlwaysVisible), api, backgroundScope)
        val models = present(AddNodeScreenPresenter(api, ControllerSession(api, AlwaysVisible), store, actions, backgroundScope), AddNodeScreenProps(emptySet()) {})
        models.first().onEvent(WizardEvent.SearchLocation("mum"))
        val searched = models.first { it.locationMatches.isNotEmpty() }
        assertEquals("Mumbai, India", searched.locationMatches.single().label)
        searched.onEvent(WizardEvent.PickLocation("in-mumbai"))
        val picked = models.first { it[WizardField.LocationName] == "Mumbai" }
        assertEquals("AS", picked[WizardField.Region])
        assertEquals(19.08 to 72.88, picked.pick)
        assertEquals("", picked.locationQuery)

        picked.onEvent(WizardEvent.PickOnMap(32.801, -96.79))
        val clicked = models.first { it[WizardField.LocationName] == "Dallas, Texas" }
        assertEquals("32.8", clicked[WizardField.Latitude], "rounded to two decimals")
        assertEquals("NA", clicked[WizardField.Region])
    }

    @Test
    fun savingALocationAndTheHomelab() = runTest {
        val api = FakeControllerApi()
        val session = ControllerSession(api, AlwaysVisible)
        val actions = ActionRunner(session, api, backgroundScope)
        val models = present(LocationsScreenPresenter(api, actions, homegap.presentation.data.Confirmations()), LocationsScreenProps)
        val loaded = models.first { !it.loading }
        assertEquals(3, loaded.rows.size)
        loaded.onEvent(LocationsEvent.Add)
        models.first { it.editor != null }.onEvent(LocationsEvent.Save)
        assertNotNull(models.first { it.editor?.error != null }.editor?.error, "id, name and coordinates are required")
        listOf(LocationField.Id to "xx-test", LocationField.Name to "Testville").forEach { (f, v) -> models.value.onEvent(LocationsEvent.Change(f, v)) }
        models.value.onEvent(LocationsEvent.PickForEditor(45.123, 7.456))
        val placed = models.first { it.editor?.pick == (45.12 to 7.46) }
        assertEquals("EU", placed.editor!![LocationField.Region], "the region is guessed from the point")
        placed.onEvent(LocationsEvent.Save)
        models.first { it.editor == null }
        assertTrue("save-location xx-test Testville" in api.calls)

        models.value.onEvent(LocationsEvent.HomelabFromCatalog("us-dallas"))
        models.first { it.homelab.pending != null }.onEvent(LocationsEvent.SaveHomelab)
        models.first { it.homelab.pending == null }
        assertTrue("homelab-location Dallas, Texas" in api.calls)
    }
}
