package fraggle.tools.time

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class TimeToolsTest {

    private val tool = GetCurrentTimeTool()

    @Nested
    inner class `Default timezone` {

        @Test
        fun `returns current time with host timezone`() = runTest {
            val result = tool.execute(GetCurrentTimeTool.Args())
            val hostTz = TimeZone.currentSystemDefault().id
            assertContains(result, hostTz)
            // ISO 8601-ish format: contains date separator and time separator
            assertContains(result, "T")
        }
    }

    @Nested
    inner class `Specific timezone` {

        @Test
        fun `returns time for valid IANA timezone`() = runTest {
            val result = tool.execute(GetCurrentTimeTool.Args(timezone = "America/New_York"))
            assertContains(result, "America/New_York")
            assertContains(result, "T")
        }

        @Test
        fun `returns time for UTC`() = runTest {
            val result = tool.execute(GetCurrentTimeTool.Args(timezone = "UTC"))
            assertContains(result, "UTC")
        }

        @Test
        fun `returns error for invalid timezone`() = runTest {
            val result = tool.execute(GetCurrentTimeTool.Args(timezone = "Not/A/Timezone"))
            assertTrue(result.startsWith("Error:"))
            assertContains(result, "Not/A/Timezone")
        }
    }

    @Nested
    inner class `Day of week` {

        @Test
        fun `includes day of week in output`() = runTest {
            val result = tool.execute(GetCurrentTimeTool.Args(timezone = "Europe/London"))
            val days = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
            assertTrue(days.any { it in result }, "Expected day of week in: $result")
        }
    }
}
