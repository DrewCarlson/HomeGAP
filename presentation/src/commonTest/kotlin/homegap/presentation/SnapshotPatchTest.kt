package homegap.presentation

import homegap.presentation.data.AlwaysVisible
import homegap.api.Health
import homegap.api.ServerEvent
import homegap.api.snapshotFields
import homegap.api.snapshotPatch
import homegap.presentation.data.ControllerSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotPatchTest {
    private val before = snapshot(
        nodes = listOf(
            node("a1"),
            node("b2"),
        ),
    )

    @Test
    fun patchCarriesOnlyChangedFields() {
        val after = before.copy(
            generatedAt = NOW + 5_000,
            nodes = listOf(
                node("a1"),
                node(id = "b2", health = Health.DOWN),
            ),
        )
        val patch = snapshotPatch(
            previous = snapshotFields(before),
            current = snapshotFields(after),
            at = after.generatedAt,
        )
        assertEquals(setOf("nodes"), patch?.set?.keys)
        assertTrue(patch?.unset.orEmpty().isEmpty())
    }

    @Test
    fun noPatchWhenOnlyTheTimeChanged() {
        val patch = snapshotPatch(
            previous = snapshotFields(before),
            current = snapshotFields(before.copy(generatedAt = NOW + 5_000)),
            at = NOW + 5_000,
        )
        assertNull(patch)
    }

    @Test
    fun sessionRebuildsTheSnapshotFromPatches() {
        val after = before.copy(
            generatedAt = NOW + 5_000,
            paused = true,
            lastReconcile = null,
            nodes = listOf(node(id = "a1", job = "updating")),
        )
        val session = ControllerSession(FakeControllerApi(), AlwaysVisible)
        session.onEvent(ServerEvent.Status(before))
        session.onEvent(
            snapshotPatch(
                previous = snapshotFields(before),
                current = snapshotFields(after),
                at = after.generatedAt,
            )!!
        )
        assertEquals(after, session.status.value)
    }
}
