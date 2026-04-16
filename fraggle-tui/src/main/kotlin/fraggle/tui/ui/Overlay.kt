package fraggle.tui.ui

/**
 * Declarative positioning for an overlay component.
 *
 * An overlay is a floating surface rendered on top of the main component
 * tree — modals, popups, autocomplete menus, tool approval prompts. This
 * data class captures *where* and *how large* to draw one; the runtime
 * consults [OverlayLayout.resolve] to turn these hints into concrete
 * `row`/`col`/`width`/`maxHeight` pixels.
 *
 * Values default to what makes sense for a centered modal: centered in the
 * viewport, no explicit size, no margins. An explicit [row]/[col] (either
 * absolute cells or a `Percent(0..1)` relative to the available space)
 * overrides the [anchor]-based positioning.
 */
data class OverlayOptions(
    /** Absolute or percentage width. Defaults to min(80, availableWidth). */
    val width: SizeValue? = null,
    /**
     * Not currently consumed by [OverlayLayout.resolve] but kept in the
     * data model so future compositing can honor it.
     */
    val height: SizeValue? = null,
    /** Absolute minimum width in cells. Applied after resolving [width]. */
    val minWidth: Int? = null,
    /** Absolute or percentage maximum height in rows. */
    val maxHeight: SizeValue? = null,
    /** Anchor used when neither [row] nor [col] is set. */
    val anchor: Anchor = Anchor.CENTER,
    /** Explicit row position. Overrides [anchor]'s row component. */
    val row: SizeValue? = null,
    /** Explicit column position. Overrides [anchor]'s col component. */
    val col: SizeValue? = null,
    /** Extra horizontal offset (positive = right). Applied after positioning. */
    val offsetX: Int = 0,
    /** Extra vertical offset (positive = down). Applied after positioning. */
    val offsetY: Int = 0,
    /** Margin from the terminal edges. Shrinks the available area for layout. */
    val margin: Margin = Margin.ZERO,
    /** If true, the runtime should not capture keyboard focus on show. */
    val nonCapturing: Boolean = false,
) {
    /**
     * Four-sided margin in cells (top/right/bottom/left). Each side must be
     * non-negative; negative values are clamped to zero at resolve time.
     */
    data class Margin(val top: Int, val right: Int, val bottom: Int, val left: Int) {
        companion object {
            val ZERO: Margin = Margin(0, 0, 0, 0)

            /** Convenience: same margin on all four sides. */
            fun all(value: Int): Margin = Margin(value, value, value, value)
        }
    }

    /**
     * Anchor positions. The anchor determines both row and col when neither is
     * set explicitly. `CENTER` centers in both axes; the remaining eight names
     * describe one corner or edge.
     */
    enum class Anchor {
        CENTER,
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT,
        TOP_CENTER,
        BOTTOM_CENTER,
        LEFT_CENTER,
        RIGHT_CENTER,
    }
}

/**
 * A size that is either an absolute number of cells or a fraction (0.0..1.0)
 * of the reference size.
 */
sealed class SizeValue {
    /** An absolute size, in terminal cells. Negative values are allowed and will be clamped at resolve time. */
    data class Absolute(val cells: Int) : SizeValue()

    /**
     * A fractional size. [fraction] is in the inclusive range `0.0..1.0`;
     * values outside that range throw [IllegalArgumentException] at
     * construction time.
     */
    data class Percent(val fraction: Double) : SizeValue() {
        init {
            require(fraction in 0.0..1.0) { "fraction out of range: $fraction (expected 0.0..1.0)" }
        }
    }

    companion object {
        /** Convenience: produce a [Percent] from an integer percent (e.g. 50). */
        fun percent(wholePercent: Int): Percent = Percent(wholePercent.coerceIn(0, 100) / 100.0)
    }
}

/**
 * Resolve final position/size for an overlay.
 *
 * The logic:
 *
 * 1. Shrink the terminal to the margin-adjusted "available" rectangle.
 * 2. Resolve [OverlayOptions.width] — default `min(80, availWidth)`,
 *    clamped to `[1, availWidth]` after applying [OverlayOptions.minWidth].
 * 3. Resolve [OverlayOptions.maxHeight] similarly, clamped to
 *    `[1, availHeight]`. May be null (no height cap).
 * 4. Compute an effective overlay height (= `min(overlayHeight, maxHeight)`).
 * 5. Resolve row: explicit [OverlayOptions.row] wins, else anchor-based.
 *    `Percent(p)` means `p * (availHeight - effectiveHeight)` from the
 *    top-of-available-area.
 * 6. Resolve col analogously.
 * 7. Apply [OverlayOptions.offsetX]/[OverlayOptions.offsetY].
 * 8. Clamp final row/col to the margin-bounded terminal rectangle so the
 *    overlay stays fully on-screen.
 */
object OverlayLayout {

    /** Resolved layout ready for the compositor. */
    data class Resolved(
        val row: Int,
        val col: Int,
        val width: Int,
        val maxHeight: Int?,
    )

    /**
     * Resolve [options] for a viewport of [termWidth] × [termHeight] cells and
     * an overlay whose natural height is [overlayHeight] rows.
     *
     * [overlayHeight] may be 0 — callers that do "width-first then render,
     * then re-resolve with the actual height" use that to compute the
     * width without knowing the final row count.
     */
    fun resolve(
        options: OverlayOptions,
        overlayHeight: Int,
        termWidth: Int,
        termHeight: Int,
    ): Resolved {
        // Margins: clamp to non-negative.
        val marginTop = options.margin.top.coerceAtLeast(0)
        val marginRight = options.margin.right.coerceAtLeast(0)
        val marginBottom = options.margin.bottom.coerceAtLeast(0)
        val marginLeft = options.margin.left.coerceAtLeast(0)

        val safeTermWidth = termWidth.coerceAtLeast(1)
        val safeTermHeight = termHeight.coerceAtLeast(1)

        // Available space after margins. Always at least 1 cell per axis.
        val availWidth = (safeTermWidth - marginLeft - marginRight).coerceAtLeast(1)
        val availHeight = (safeTermHeight - marginTop - marginBottom).coerceAtLeast(1)

        // === Resolve width ===
        val defaultWidth = minOf(80, availWidth)
        var width = resolveSize(options.width, safeTermWidth) ?: defaultWidth
        options.minWidth?.let { width = maxOf(width, it) }
        width = width.coerceIn(1, availWidth)

        // === Resolve maxHeight ===
        val maxHeight = resolveSize(options.maxHeight, safeTermHeight)?.coerceIn(1, availHeight)

        // Effective overlay height, factoring maxHeight.
        val clampedOverlayHeight = overlayHeight.coerceAtLeast(0)
        val effectiveHeight = if (maxHeight != null) {
            minOf(clampedOverlayHeight, maxHeight)
        } else {
            clampedOverlayHeight
        }

        // === Resolve row ===
        var row = when (val r = options.row) {
            null -> resolveAnchorRow(options.anchor, effectiveHeight, availHeight, marginTop)
            is SizeValue.Absolute -> r.cells
            is SizeValue.Percent -> {
                val maxRow = (availHeight - effectiveHeight).coerceAtLeast(0)
                marginTop + (maxRow * r.fraction).toInt()
            }
        }

        // === Resolve col ===
        var col = when (val c = options.col) {
            null -> resolveAnchorCol(options.anchor, width, availWidth, marginLeft)
            is SizeValue.Absolute -> c.cells
            is SizeValue.Percent -> {
                val maxCol = (availWidth - width).coerceAtLeast(0)
                marginLeft + (maxCol * c.fraction).toInt()
            }
        }

        // === Apply offsets ===
        row += options.offsetY
        col += options.offsetX

        // === Clamp to margin-bounded terminal ===
        // Max-row / max-col allow the overlay to hit the bottom/right margin
        // while still leaving room for its full effective height/width.
        val maxRowBound = (safeTermHeight - marginBottom - effectiveHeight).coerceAtLeast(marginTop)
        val maxColBound = (safeTermWidth - marginRight - width).coerceAtLeast(marginLeft)
        row = row.coerceIn(marginTop, maxRowBound)
        col = col.coerceIn(marginLeft, maxColBound)

        return Resolved(row = row, col = col, width = width, maxHeight = maxHeight)
    }

    /** Convert a [SizeValue] to a concrete cell count, or null when absent. */
    private fun resolveSize(value: SizeValue?, reference: Int): Int? = when (value) {
        null -> null
        is SizeValue.Absolute -> value.cells
        is SizeValue.Percent -> (reference * value.fraction).toInt()
    }

    /** Anchor → row pixel (top of overlay). */
    private fun resolveAnchorRow(
        anchor: OverlayOptions.Anchor,
        height: Int,
        availHeight: Int,
        marginTop: Int,
    ): Int = when (anchor) {
        OverlayOptions.Anchor.TOP_LEFT,
        OverlayOptions.Anchor.TOP_CENTER,
        OverlayOptions.Anchor.TOP_RIGHT,
        -> marginTop

        OverlayOptions.Anchor.BOTTOM_LEFT,
        OverlayOptions.Anchor.BOTTOM_CENTER,
        OverlayOptions.Anchor.BOTTOM_RIGHT,
        -> marginTop + availHeight - height

        OverlayOptions.Anchor.LEFT_CENTER,
        OverlayOptions.Anchor.CENTER,
        OverlayOptions.Anchor.RIGHT_CENTER,
        -> marginTop + (availHeight - height) / 2
    }

    /** Anchor → col pixel (left of overlay). */
    private fun resolveAnchorCol(
        anchor: OverlayOptions.Anchor,
        width: Int,
        availWidth: Int,
        marginLeft: Int,
    ): Int = when (anchor) {
        OverlayOptions.Anchor.TOP_LEFT,
        OverlayOptions.Anchor.LEFT_CENTER,
        OverlayOptions.Anchor.BOTTOM_LEFT,
        -> marginLeft

        OverlayOptions.Anchor.TOP_RIGHT,
        OverlayOptions.Anchor.RIGHT_CENTER,
        OverlayOptions.Anchor.BOTTOM_RIGHT,
        -> marginLeft + availWidth - width

        OverlayOptions.Anchor.TOP_CENTER,
        OverlayOptions.Anchor.CENTER,
        OverlayOptions.Anchor.BOTTOM_CENTER,
        -> marginLeft + (availWidth - width) / 2
    }
}
