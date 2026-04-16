package fraggle.tui.ui

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OverlayLayoutTest {

    // Reasonable default terminal dimensions for most tests.
    private val termWidth = 100
    private val termHeight = 40

    @Nested
    inner class SizeValueConstruction {
        @Test
        fun `Percent rejects values below zero`() {
            assertFailsWith<IllegalArgumentException> { SizeValue.Percent(-0.1) }
        }

        @Test
        fun `Percent rejects values above one`() {
            assertFailsWith<IllegalArgumentException> { SizeValue.Percent(1.5) }
        }

        @Test
        fun `Percent accepts the endpoints 0_0 and 1_0`() {
            SizeValue.Percent(0.0)
            SizeValue.Percent(1.0)
        }

        @Test
        fun `percent helper clamps and converts whole-percent ints`() {
            assertEquals(SizeValue.Percent(0.5), SizeValue.percent(50))
            assertEquals(SizeValue.Percent(0.0), SizeValue.percent(-10))
            assertEquals(SizeValue.Percent(1.0), SizeValue.percent(200))
        }
    }

    @Nested
    inner class Defaults {
        @Test
        fun `default options center overlay of height 10 in a 100x40 viewport`() {
            val options = OverlayOptions()
            val r = OverlayLayout.resolve(options, overlayHeight = 10, termWidth = 100, termHeight = 40)
            // Default width = min(80, availWidth=100) = 80.
            assertEquals(80, r.width)
            // Center: col = (100 - 80) / 2 = 10
            assertEquals(10, r.col)
            // Center: row = (40 - 10) / 2 = 15
            assertEquals(15, r.row)
            // No maxHeight set.
            assertNull(r.maxHeight)
        }

        @Test
        fun `default width is clamped to available width when term smaller than 80`() {
            val r = OverlayLayout.resolve(OverlayOptions(), overlayHeight = 5, termWidth = 40, termHeight = 20)
            assertEquals(40, r.width)
            assertEquals(0, r.col)
        }
    }

    @Nested
    inner class WidthResolution {
        @Test
        fun `absolute width wins over default`() {
            val r = OverlayLayout.resolve(
                OverlayOptions(width = SizeValue.Absolute(30)),
                overlayHeight = 5,
                termWidth = 100,
                termHeight = 40,
            )
            assertEquals(30, r.width)
        }

        @Test
        fun `percent width scales against terminal width`() {
            val r = OverlayLayout.resolve(
                OverlayOptions(width = SizeValue.Percent(0.5)),
                overlayHeight = 5,
                termWidth = 100,
                termHeight = 40,
            )
            assertEquals(50, r.width)
        }

        @Test
        fun `width is clamped to available width`() {
            val r = OverlayLayout.resolve(
                OverlayOptions(width = SizeValue.Absolute(500)),
                overlayHeight = 5,
                termWidth = 40,
                termHeight = 20,
            )
            assertEquals(40, r.width)
        }

        @Test
        fun `minWidth expands a too-narrow width`() {
            val r = OverlayLayout.resolve(
                OverlayOptions(width = SizeValue.Absolute(10), minWidth = 30),
                overlayHeight = 5,
                termWidth = 100,
                termHeight = 40,
            )
            assertEquals(30, r.width)
        }

        @Test
        fun `minWidth still clamped to available width`() {
            val r = OverlayLayout.resolve(
                OverlayOptions(minWidth = 500),
                overlayHeight = 5,
                termWidth = 40,
                termHeight = 20,
            )
            assertEquals(40, r.width)
        }

        @Test
        fun `width is never smaller than 1`() {
            val r = OverlayLayout.resolve(
                OverlayOptions(width = SizeValue.Absolute(0)),
                overlayHeight = 5,
                termWidth = 100,
                termHeight = 40,
            )
            assertEquals(1, r.width)
        }
    }

    @Nested
    inner class MaxHeightResolution {
        @Test
        fun `absolute maxHeight passes through`() {
            val r = OverlayLayout.resolve(
                OverlayOptions(maxHeight = SizeValue.Absolute(15)),
                overlayHeight = 30,
                termWidth = 100,
                termHeight = 40,
            )
            assertEquals(15, r.maxHeight)
        }

        @Test
        fun `percent maxHeight scales against term height`() {
            val r = OverlayLayout.resolve(
                OverlayOptions(maxHeight = SizeValue.Percent(0.5)),
                overlayHeight = 30,
                termWidth = 100,
                termHeight = 40,
            )
            assertEquals(20, r.maxHeight)
        }

        @Test
        fun `null when no maxHeight requested`() {
            val r = OverlayLayout.resolve(
                OverlayOptions(),
                overlayHeight = 10,
                termWidth = 100,
                termHeight = 40,
            )
            assertNull(r.maxHeight)
        }

        @Test
        fun `maxHeight clamped to available height`() {
            val r = OverlayLayout.resolve(
                OverlayOptions(maxHeight = SizeValue.Absolute(500)),
                overlayHeight = 100,
                termWidth = 100,
                termHeight = 40,
            )
            assertEquals(40, r.maxHeight)
        }
    }

    @Nested
    inner class Anchors {
        private val h = 10
        private val w = 20

        private fun resolve(anchor: OverlayOptions.Anchor) = OverlayLayout.resolve(
            OverlayOptions(width = SizeValue.Absolute(w), anchor = anchor),
            overlayHeight = h,
            termWidth = termWidth,
            termHeight = termHeight,
        )

        @Test
        fun `CENTER puts the overlay in the middle of the viewport`() {
            val r = resolve(OverlayOptions.Anchor.CENTER)
            assertEquals((termWidth - w) / 2, r.col)
            assertEquals((termHeight - h) / 2, r.row)
        }

        @Test
        fun `TOP_LEFT places at origin`() {
            val r = resolve(OverlayOptions.Anchor.TOP_LEFT)
            assertEquals(0, r.row)
            assertEquals(0, r.col)
        }

        @Test
        fun `TOP_RIGHT places against right margin, at top`() {
            val r = resolve(OverlayOptions.Anchor.TOP_RIGHT)
            assertEquals(0, r.row)
            assertEquals(termWidth - w, r.col)
        }

        @Test
        fun `BOTTOM_LEFT places against bottom edge, at left`() {
            val r = resolve(OverlayOptions.Anchor.BOTTOM_LEFT)
            assertEquals(termHeight - h, r.row)
            assertEquals(0, r.col)
        }

        @Test
        fun `BOTTOM_RIGHT places against bottom-right`() {
            val r = resolve(OverlayOptions.Anchor.BOTTOM_RIGHT)
            assertEquals(termHeight - h, r.row)
            assertEquals(termWidth - w, r.col)
        }

        @Test
        fun `TOP_CENTER centers horizontally at top`() {
            val r = resolve(OverlayOptions.Anchor.TOP_CENTER)
            assertEquals(0, r.row)
            assertEquals((termWidth - w) / 2, r.col)
        }

        @Test
        fun `BOTTOM_CENTER centers horizontally at bottom`() {
            val r = resolve(OverlayOptions.Anchor.BOTTOM_CENTER)
            assertEquals(termHeight - h, r.row)
            assertEquals((termWidth - w) / 2, r.col)
        }

        @Test
        fun `LEFT_CENTER centers vertically at left edge`() {
            val r = resolve(OverlayOptions.Anchor.LEFT_CENTER)
            assertEquals((termHeight - h) / 2, r.row)
            assertEquals(0, r.col)
        }

        @Test
        fun `RIGHT_CENTER centers vertically at right edge`() {
            val r = resolve(OverlayOptions.Anchor.RIGHT_CENTER)
            assertEquals((termHeight - h) / 2, r.row)
            assertEquals(termWidth - w, r.col)
        }
    }

    @Nested
    inner class ExplicitPosition {
        @Test
        fun `absolute row wins over anchor`() {
            val r = OverlayLayout.resolve(
                OverlayOptions(
                    width = SizeValue.Absolute(20),
                    anchor = OverlayOptions.Anchor.BOTTOM_CENTER,
                    row = SizeValue.Absolute(5),
                ),
                overlayHeight = 10,
                termWidth = 100,
                termHeight = 40,
            )
            assertEquals(5, r.row)
        }

        @Test
        fun `absolute col wins over anchor`() {
            val r = OverlayLayout.resolve(
                OverlayOptions(
                    width = SizeValue.Absolute(20),
                    anchor = OverlayOptions.Anchor.CENTER,
                    col = SizeValue.Absolute(3),
                ),
                overlayHeight = 10,
                termWidth = 100,
                termHeight = 40,
            )
            assertEquals(3, r.col)
        }

        @Test
        fun `percent row computes from available height minus effective height`() {
            // availHeight = 40, effectiveHeight = 10, so 0% = 0, 100% = 30, 50% = 15.
            val r = OverlayLayout.resolve(
                OverlayOptions(
                    width = SizeValue.Absolute(20),
                    row = SizeValue.Percent(0.5),
                ),
                overlayHeight = 10,
                termWidth = 100,
                termHeight = 40,
            )
            assertEquals(15, r.row)
        }

        @Test
        fun `percent col computes from available width minus width`() {
            // availWidth = 100, width = 20, so 0% = 0, 100% = 80, 25% = 20.
            val r = OverlayLayout.resolve(
                OverlayOptions(
                    width = SizeValue.Absolute(20),
                    col = SizeValue.Percent(0.25),
                ),
                overlayHeight = 10,
                termWidth = 100,
                termHeight = 40,
            )
            assertEquals(20, r.col)
        }

        @Test
        fun `percent row 0_0 and 1_0 hit the top and bottom bounds`() {
            val base = OverlayOptions(width = SizeValue.Absolute(20))
            val top = OverlayLayout.resolve(
                base.copy(row = SizeValue.Percent(0.0)),
                overlayHeight = 10, termWidth = 100, termHeight = 40,
            )
            val bottom = OverlayLayout.resolve(
                base.copy(row = SizeValue.Percent(1.0)),
                overlayHeight = 10, termWidth = 100, termHeight = 40,
            )
            assertEquals(0, top.row)
            assertEquals(30, bottom.row) // 40 - 10
        }
    }

    @Nested
    inner class Offsets {
        @Test
        fun `offsetX and offsetY shift the resolved position`() {
            val r = OverlayLayout.resolve(
                OverlayOptions(
                    width = SizeValue.Absolute(10),
                    anchor = OverlayOptions.Anchor.TOP_LEFT,
                    offsetX = 3,
                    offsetY = 2,
                ),
                overlayHeight = 5,
                termWidth = 100,
                termHeight = 40,
            )
            assertEquals(2, r.row)
            assertEquals(3, r.col)
        }

        @Test
        fun `negative offset shifts position toward origin`() {
            val r = OverlayLayout.resolve(
                OverlayOptions(
                    width = SizeValue.Absolute(10),
                    anchor = OverlayOptions.Anchor.CENTER,
                    offsetX = -5,
                    offsetY = -3,
                ),
                overlayHeight = 4,
                termWidth = 100,
                termHeight = 40,
            )
            assertEquals((40 - 4) / 2 - 3, r.row)
            assertEquals((100 - 10) / 2 - 5, r.col)
        }
    }

    @Nested
    inner class Margins {
        @Test
        fun `margin shrinks the available area`() {
            val r = OverlayLayout.resolve(
                OverlayOptions(
                    width = SizeValue.Absolute(20),
                    anchor = OverlayOptions.Anchor.TOP_LEFT,
                    margin = OverlayOptions.Margin(top = 3, right = 0, bottom = 0, left = 2),
                ),
                overlayHeight = 4,
                termWidth = 100,
                termHeight = 40,
            )
            assertEquals(3, r.row)
            assertEquals(2, r.col)
        }

        @Test
        fun `BOTTOM_RIGHT anchor respects margins`() {
            val r = OverlayLayout.resolve(
                OverlayOptions(
                    width = SizeValue.Absolute(10),
                    anchor = OverlayOptions.Anchor.BOTTOM_RIGHT,
                    margin = OverlayOptions.Margin(top = 0, right = 4, bottom = 2, left = 0),
                ),
                overlayHeight = 3,
                termWidth = 100,
                termHeight = 40,
            )
            // Bottom = (availHeight = 38) + marginTop(0) - height(3) = 35.
            assertEquals(35, r.row)
            // Right = (availWidth = 96) + marginLeft(0) - width(10) = 86.
            assertEquals(86, r.col)
        }

        @Test
        fun `Margin_all applies uniform margin`() {
            val m = OverlayOptions.Margin.all(4)
            assertEquals(4, m.top)
            assertEquals(4, m.right)
            assertEquals(4, m.bottom)
            assertEquals(4, m.left)
        }

        @Test
        fun `negative margin values clamp to zero`() {
            val r = OverlayLayout.resolve(
                OverlayOptions(
                    width = SizeValue.Absolute(10),
                    anchor = OverlayOptions.Anchor.TOP_LEFT,
                    margin = OverlayOptions.Margin(top = -5, right = -2, bottom = -1, left = -3),
                ),
                overlayHeight = 3,
                termWidth = 100,
                termHeight = 40,
            )
            assertEquals(0, r.row)
            assertEquals(0, r.col)
        }
    }

    @Nested
    inner class Clamping {
        @Test
        fun `offset pushing past right edge clamps back into viewport`() {
            val r = OverlayLayout.resolve(
                OverlayOptions(
                    width = SizeValue.Absolute(10),
                    anchor = OverlayOptions.Anchor.TOP_LEFT,
                    offsetX = 200,
                ),
                overlayHeight = 3,
                termWidth = 100,
                termHeight = 40,
            )
            // Should clamp so col + width <= termWidth.
            assertEquals(90, r.col)
        }

        @Test
        fun `offset pushing past bottom edge clamps back into viewport`() {
            val r = OverlayLayout.resolve(
                OverlayOptions(
                    width = SizeValue.Absolute(10),
                    anchor = OverlayOptions.Anchor.TOP_LEFT,
                    offsetY = 200,
                ),
                overlayHeight = 5,
                termWidth = 100,
                termHeight = 40,
            )
            // Should clamp so row + height <= termHeight.
            assertEquals(35, r.row)
        }

        @Test
        fun `negative offset past left edge clamps to zero`() {
            val r = OverlayLayout.resolve(
                OverlayOptions(
                    width = SizeValue.Absolute(10),
                    anchor = OverlayOptions.Anchor.TOP_LEFT,
                    offsetX = -50,
                    offsetY = -50,
                ),
                overlayHeight = 3,
                termWidth = 100,
                termHeight = 40,
            )
            assertEquals(0, r.col)
            assertEquals(0, r.row)
        }

        @Test
        fun `absolute row outside bounds gets clamped`() {
            val r = OverlayLayout.resolve(
                OverlayOptions(
                    width = SizeValue.Absolute(10),
                    row = SizeValue.Absolute(1000),
                    col = SizeValue.Absolute(1000),
                ),
                overlayHeight = 5,
                termWidth = 100,
                termHeight = 40,
            )
            assertEquals(35, r.row)
            assertEquals(90, r.col)
        }
    }

    @Nested
    inner class EffectiveHeight {
        @Test
        fun `maxHeight caps the row-positioning height budget`() {
            // overlayHeight=100 but maxHeight=10 → layout uses 10 for effective height.
            // With anchor=BOTTOM, row = termHeight - effectiveHeight.
            val r = OverlayLayout.resolve(
                OverlayOptions(
                    width = SizeValue.Absolute(20),
                    anchor = OverlayOptions.Anchor.BOTTOM_CENTER,
                    maxHeight = SizeValue.Absolute(10),
                ),
                overlayHeight = 100,
                termWidth = 100,
                termHeight = 40,
            )
            assertEquals(30, r.row) // 40 - 10
            assertEquals(10, r.maxHeight)
        }

        @Test
        fun `overlayHeight zero is handled (width-first pass)`() {
            val r = OverlayLayout.resolve(
                OverlayOptions(width = SizeValue.Absolute(20)),
                overlayHeight = 0,
                termWidth = 100,
                termHeight = 40,
            )
            // Centered: row = (40 - 0) / 2 = 20.
            assertEquals(20, r.row)
            assertNotNull(r.width)
        }
    }

    @Nested
    inner class DegenerateDimensions {
        @Test
        fun `tiny terminal still produces valid output`() {
            val r = OverlayLayout.resolve(
                OverlayOptions(),
                overlayHeight = 1,
                termWidth = 1,
                termHeight = 1,
            )
            assertEquals(0, r.row)
            assertEquals(0, r.col)
            assertEquals(1, r.width)
        }

        @Test
        fun `zero terminal dims coerce to at least one`() {
            val r = OverlayLayout.resolve(
                OverlayOptions(),
                overlayHeight = 0,
                termWidth = 0,
                termHeight = 0,
            )
            // Should not crash; width = min(80, 1) = 1.
            assertEquals(1, r.width)
        }
    }
}
