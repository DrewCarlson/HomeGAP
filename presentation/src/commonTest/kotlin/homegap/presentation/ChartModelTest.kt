package homegap.presentation

import homegap.presentation.data.Samples
import homegap.presentation.ui.ChartTone
import homegap.presentation.ui.chartOf
import homegap.presentation.ui.niceCeiling
import homegap.presentation.ui.percentile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ChartModelTest {
    private fun samples(vararg points: Pair<Long, Double?>) = Samples(
        unit = "ms",
        times = points.map { it.first },
        values = points.map { it.second },
    )

    @Test
    fun aFailedSampleBreaksTheLine() {
        val chart = chartOf(
            label = "API",
            samples = samples(3000L to 10.0, 3060L to 12.0, 3120L to null, 3180L to 11.0),
            nowSeconds = 3600,
        )
        assertEquals(listOf(2, 1), chart.segments.map { it.size })
        assertEquals("11", chart.current)
    }

    @Test
    fun aLongGapBreaksTheLine() {
        val chart = chartOf(
            label = "API",
            samples = samples(100L to 10.0, 160L to 10.0, 220L to 10.0, 1000L to 10.0),
            nowSeconds = 3600,
        )
        assertEquals(listOf(3, 1), chart.segments.map { it.size })
    }

    @Test
    fun aFlatLineStaysInsideTheBox() {
        val chart = chartOf(
            label = "API",
            samples = samples(3000L to 0.0, 3060L to 0.0),
            nowSeconds = 3600,
        )
        assertEquals("1", chart.max)
        assertEquals(listOf(0.0, 0.0), chart.segments.single().map { it.y })
    }

    @Test
    fun overTheThresholdTurnsAmber() {
        val chart = chartOf(
            label = "API",
            samples = samples(3500L to 412.0),
            nowSeconds = 3600,
            threshold = 300.0,
        )
        assertEquals(ChartTone.Amber, chart.tone)
        assertEquals("500", chart.max)
        assertNotNull(chart.threshold)
    }

    @Test
    fun saysWhyThereIsNothingToDraw() {
        assertNotNull(chartOf(label = "API", samples = null, nowSeconds = 3600).empty)
        assertNull(chartOf(label = "API", samples = samples(3500L to 1.0), nowSeconds = 3600).empty)
    }

    @Test
    fun scalesAndPercentiles() {
        assertEquals(500.0, niceCeiling(412.0))
        assertEquals(20.0, niceCeiling(11.0))
        assertEquals(1.0, niceCeiling(0.0))
        assertEquals(95.0, percentile((1..100).map { it.toDouble() }, 0.95))
        assertNull(percentile(emptyList(), 0.95))
    }
}
