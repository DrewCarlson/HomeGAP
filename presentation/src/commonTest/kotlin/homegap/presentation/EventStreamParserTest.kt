package homegap.presentation

import homegap.presentation.data.EventStreamParser
import homegap.presentation.data.ServerEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class EventStreamParserTest {
    @Test
    fun assemblesEventsAcrossChunksAndSkipsComments() {
        val parser = EventStreamParser()
        assertEquals(emptyList(), parser.feed("event: status\ndata: {\"a\""))
        assertEquals(listOf(ServerEvent("status", "{\"a\":1}")), parser.feed(":1}\n\n: keepalive\n\n"))
        assertEquals(listOf(ServerEvent("message", "x\ny")), parser.feed("data: x\ndata: y\n\n"))
    }
}
