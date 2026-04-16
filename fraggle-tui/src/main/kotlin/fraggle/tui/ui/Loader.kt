package fraggle.tui.ui

import fraggle.tui.core.Component
import fraggle.tui.text.Ansi
import fraggle.tui.text.padRightToWidth
import fraggle.tui.text.truncateToWidth
import fraggle.tui.text.visibleWidth

/**
 * Animated single-line spinner with an optional label.
 *
 * Renders as `{frame} {label}` where `{frame}` is the current glyph from
 * [frames] (cycles each call to [tick]) and `{label}` is right-padded with
 * spaces so the total line width equals the viewport width. If the label
 * doesn't fit alongside the spinner and a separating space, it is truncated
 * with an ellipsis.
 *
 * The loader does NOT drive its own timer — the host app (or TUI runtime)
 * is responsible for calling [tick] at a regular interval and then triggering
 * a render. Keeping the timer out of here means a Loader is safe to construct
 * in a test without a coroutine scope.
 */
class Loader(
    label: String = "Loading...",
    frames: List<String> = DEFAULT_FRAMES,
    color: String? = null,
) : Component {

    private var label: String = label
    private var frames: List<String> = frames.ifEmpty { DEFAULT_FRAMES }
    private var color: String? = color
    private var currentFrame: Int = 0

    /** Replace the displayed label. Does not reset the animation frame. */
    fun setLabel(label: String) {
        this.label = label
    }

    /** Swap the frame sequence. Resets the current frame index to 0. */
    fun setFrames(frames: List<String>) {
        this.frames = frames.ifEmpty { DEFAULT_FRAMES }
        this.currentFrame = 0
    }

    /** Set (or clear) the ANSI color prefix applied to the whole line. */
    fun setColor(color: String?) {
        this.color = color
    }

    /** Advance to the next frame, wrapping around at the end of [frames]. */
    fun tick() {
        currentFrame = (currentFrame + 1) % frames.size
    }

    /** Current frame index (0-based). */
    val frame: Int get() = currentFrame

    override fun render(width: Int): List<String> {
        if (width <= 0) return emptyList()

        val frameGlyph = frames[currentFrame % frames.size]
        val frameWidth = visibleWidth(frameGlyph)

        // Frame alone overflows the viewport — emit a single right-padded row
        // with the frame truncated to fit. (Pathological, but width=1 or 2 is
        // a real thing on narrow panes.)
        if (frameWidth >= width) {
            val clippedFrame = truncateToWidth(frameGlyph, width)
            val line = padRightToWidth(clippedFrame, width)
            return listOf(colorize(line))
        }

        // Frame + space + label, then right-pad to exact width.
        val labelBudget = width - frameWidth - 1
        val labelText = if (visibleWidth(label) > labelBudget) {
            truncateToWidth(label, labelBudget)
        } else {
            label
        }

        val raw = "$frameGlyph $labelText"
        val padded = padRightToWidth(raw, width)
        return listOf(colorize(padded))
    }

    private fun colorize(line: String): String {
        val c = color
        return if (c.isNullOrEmpty()) line else "$c$line${Ansi.RESET}"
    }

    companion object {
        /** Braille dots spinner. */
        val DEFAULT_FRAMES: List<String> = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

        /** Fallback ASCII "typing" dots. */
        val DOTS: List<String> = listOf("   ", ".  ", ".. ", "...")
    }
}
