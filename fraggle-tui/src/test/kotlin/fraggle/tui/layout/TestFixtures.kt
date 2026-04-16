package fraggle.tui.layout

import com.jakewharton.mosaic.terminal.KeyboardEvent
import fraggle.tui.core.Component

/**
 * Component stubs for tests that don't want to couple to any real rendering.
 *
 * Each instance exposes the width it was last asked to render at plus the lines
 * it emits so tests can make assertions without touching [fraggle.tui.text]
 * (whose functions are stubbed out until Wave 1A lands).
 */
internal class FakeComponent(
    private val lines: List<String>,
) : Component {
    var lastWidth: Int = -1
        private set

    var renderCount: Int = 0
        private set

    var invalidateCount: Int = 0
        private set

    override fun render(width: Int): List<String> {
        lastWidth = width
        renderCount++
        return lines
    }

    override fun handleInput(key: KeyboardEvent): Boolean = false

    override fun invalidate() {
        invalidateCount++
    }
}

/** Convenience — produces a FakeComponent for [count] lines of the same content. */
internal fun fakeLines(count: Int, content: String = "X"): FakeComponent =
    FakeComponent(List(count) { content })
