package fraggle.agent.loop

import fraggle.provider.ThinkingLevel
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThinkingControllerTest {

    @Test
    fun `defaults to null override`() {
        val controller = ThinkingController()
        assertNull(controller.level)
    }

    @Test
    fun `accepts initial value`() {
        val controller = ThinkingController(ThinkingLevel.HIGH)
        assertEquals(ThinkingLevel.HIGH, controller.level)
    }

    @Test
    fun `level can be reassigned`() {
        val controller = ThinkingController()
        controller.level = ThinkingLevel.LOW
        assertEquals(ThinkingLevel.LOW, controller.level)
        controller.level = ThinkingLevel.OFF
        assertEquals(ThinkingLevel.OFF, controller.level)
        controller.level = null
        assertNull(controller.level)
    }
}
