package homegap.presentation

import homegap.presentation.routing.Location
import homegap.presentation.routing.Route
import kotlin.test.Test
import kotlin.test.assertEquals

class LocationTest {
    @Test
    fun readsTheRouteAndTheSelection() {
        assertEquals(Location(Route.Nodes, "sg1"), Location.fromPath("#nodes/sg1"))
        assertEquals(Location(Route.Nodes), Location.fromPath("nodes"))
        assertEquals(Location(Route.Overview), Location.fromPath("#nowhere"))
        assertEquals(Location(Route.Overview), Location.fromPath(""))
    }

    @Test
    fun selectionsSurviveTheRoundTrip() {
        listOf("sg1", "*.dev.example.com", "a/b #c", "café").forEach { selection ->
            val location = Location(Route.Services, selection)
            assertEquals(location, Location.fromPath("#" + location.path))
        }
    }

    @Test
    fun plainSelectionsStayReadable() {
        assertEquals("services/home.example.com", Location(Route.Services, "home.example.com").path)
    }
}
