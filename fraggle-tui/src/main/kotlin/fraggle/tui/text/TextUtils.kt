package fraggle.tui.text

import java.text.BreakIterator

/**
 * Text utilities for ANSI-aware string measurement, slicing, wrapping, and
 * truncation. All functions here are pure — no terminal I/O, no caching.
 *
 * ## Visible width
 *
 * "Visible width" means the number of monospace terminal cells a string occupies
 * when rendered. It differs from [String.length] in three ways:
 *
 * 1. **ANSI escape sequences** (`\u001b[1;31m`, OSC 8 hyperlinks, etc.) take
 *    zero cells. [visibleWidth] must skip over them.
 * 2. **East Asian wide characters** (CJK ideographs, full-width kana, etc.)
 *    take two cells each.
 * 3. **Emoji and zero-width joiner sequences** can take two cells for a single
 *    base character, plus zero for combining modifiers (VS16, skin tones).
 *
 * Getting this right is load-bearing: every line written to the terminal is
 * width-checked against [visibleWidth]; a miscount causes ghost rendering.
 */

// ────────────────────────────────────────────────────────────────────────────
// East Asian Width lookup tables (UAX #11). Ported verbatim from the npm
// `get-east-asian-width` package (v1.5.0). Each array is a flat list of
// inclusive [start, end] code-point pairs, sorted ascending. Binary search
// over pairs is the lookup primitive.
// ────────────────────────────────────────────────────────────────────────────

private val FULLWIDTH_RANGES = intArrayOf(
    12288, 12288, 65281, 65376, 65504, 65510,
)

private val WIDE_RANGES = intArrayOf(
    4352, 4447, 8986, 8987, 9001, 9002, 9193, 9196, 9200, 9200, 9203, 9203,
    9725, 9726, 9748, 9749, 9776, 9783, 9800, 9811, 9855, 9855, 9866, 9871,
    9875, 9875, 9889, 9889, 9898, 9899, 9917, 9918, 9924, 9925, 9934, 9934,
    9940, 9940, 9962, 9962, 9970, 9971, 9973, 9973, 9978, 9978, 9981, 9981,
    9989, 9989, 9994, 9995, 10024, 10024, 10060, 10060, 10062, 10062,
    10067, 10069, 10071, 10071, 10133, 10135, 10160, 10160, 10175, 10175,
    11035, 11036, 11088, 11088, 11093, 11093, 11904, 11929, 11931, 12019,
    12032, 12245, 12272, 12287, 12289, 12350, 12353, 12438, 12441, 12543,
    12549, 12591, 12593, 12686, 12688, 12773, 12783, 12830, 12832, 12871,
    12880, 42124, 42128, 42182, 43360, 43388, 44032, 55203, 63744, 64255,
    65040, 65049, 65072, 65106, 65108, 65126, 65128, 65131, 94176, 94180,
    94192, 94198, 94208, 101589, 101631, 101662, 101760, 101874,
    110576, 110579, 110581, 110587, 110589, 110590, 110592, 110882,
    110898, 110898, 110928, 110930, 110933, 110933, 110948, 110951,
    110960, 111355, 119552, 119638, 119648, 119670, 126980, 126980,
    127183, 127183, 127374, 127374, 127377, 127386, 127488, 127490,
    127504, 127547, 127552, 127560, 127568, 127569, 127584, 127589,
    127744, 127776, 127789, 127797, 127799, 127868, 127870, 127891,
    127904, 127946, 127951, 127955, 127968, 127984, 127988, 127988,
    127992, 128062, 128064, 128064, 128066, 128252, 128255, 128317,
    128331, 128334, 128336, 128359, 128378, 128378, 128405, 128406,
    128420, 128420, 128507, 128591, 128640, 128709, 128716, 128716,
    128720, 128722, 128725, 128728, 128732, 128735, 128747, 128748,
    128756, 128764, 128992, 129003, 129008, 129008, 129292, 129338,
    129340, 129349, 129351, 129535, 129648, 129660, 129664, 129674,
    129678, 129734, 129736, 129736, 129741, 129756, 129759, 129770,
    129775, 129784, 131072, 196605, 196608, 262141,
)

private val MIN_FULLWIDTH_CP: Int = FULLWIDTH_RANGES.first()
private val MAX_FULLWIDTH_CP: Int = FULLWIDTH_RANGES.last()
private val MIN_WIDE_CP: Int = WIDE_RANGES.first()
private val MAX_WIDE_CP: Int = WIDE_RANGES.last()

/** Hot-path range for common CJK ideographs (covers U+4E00 block). */
private val WIDE_FAST_PATH: IntArray = findWideFastPath(WIDE_RANGES)
private val WIDE_FAST_PATH_START: Int get() = WIDE_FAST_PATH[0]
private val WIDE_FAST_PATH_END: Int get() = WIDE_FAST_PATH[1]

private fun findWideFastPath(ranges: IntArray): IntArray {
    val commonCjk = 0x4E00
    var start = ranges[0]
    var end = ranges[1]
    var idx = 0
    while (idx < ranges.size) {
        val s = ranges[idx]
        val e = ranges[idx + 1]
        if (commonCjk in s..e) return intArrayOf(s, e)
        if ((e - s) > (end - start)) {
            start = s
            end = e
        }
        idx += 2
    }
    return intArrayOf(start, end)
}

/** Binary search over a flat [start, end, start, end, …] pair array. */
private fun isInRange(ranges: IntArray, codePoint: Int): Boolean {
    var low = 0
    var high = (ranges.size / 2) - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val i = mid * 2
        when {
            codePoint < ranges[i] -> high = mid - 1
            codePoint > ranges[i + 1] -> low = mid + 1
            else -> return true
        }
    }
    return false
}

private fun isFullWidth(codePoint: Int): Boolean {
    if (codePoint < MIN_FULLWIDTH_CP || codePoint > MAX_FULLWIDTH_CP) return false
    return isInRange(FULLWIDTH_RANGES, codePoint)
}

private fun isWide(codePoint: Int): Boolean {
    if (codePoint in WIDE_FAST_PATH_START..WIDE_FAST_PATH_END) return true
    if (codePoint < MIN_WIDE_CP || codePoint > MAX_WIDE_CP) return false
    return isInRange(WIDE_RANGES, codePoint)
}

/** East Asian Width for a single code point. */
private fun eastAsianWidth(codePoint: Int): Int =
    if (isFullWidth(codePoint) || isWide(codePoint)) 2 else 1

// ────────────────────────────────────────────────────────────────────────────
// Zero-width / non-printing code-point classification.
// ────────────────────────────────────────────────────────────────────────────

/**
 * Is [codePoint] zero-width in isolation? Returns true for combining marks,
 * format characters, control characters, surrogates, and Default_Ignorable
 * code points. Used both to detect zero-width clusters and to strip leading
 * non-printing code points before measuring a cluster's base character.
 */
private fun isZeroWidthCp(codePoint: Int): Boolean {
    // Fast path: ASCII printable never zero-width.
    if (codePoint in 0x20..0x7E) return false

    // C0/C1 controls.
    if (codePoint < 0x20) return true
    if (codePoint in 0x7F..0x9F) return true

    // Surrogates.
    if (codePoint in 0xD800..0xDFFF) return true

    when (Character.getType(codePoint).toByte()) {
        Character.NON_SPACING_MARK,
        Character.ENCLOSING_MARK,
        Character.COMBINING_SPACING_MARK,
        Character.FORMAT,
        Character.CONTROL,
        Character.SURROGATE,
        -> return true
    }

    // Default_Ignorable_Code_Point — a small set Java doesn't expose directly.
    return isDefaultIgnorable(codePoint)
}

/** Default_Ignorable_Code_Point subset from the Unicode data. */
private fun isDefaultIgnorable(cp: Int): Boolean = when (cp) {
    0x00AD, 0x034F, 0x061C, 0x115F, 0x1160, 0x17B4, 0x17B5,
    0x3164, 0xFFA0, -> true
    in 0x180B..0x180F -> true // Mongolian FVSs + MVS
    in 0x200B..0x200F -> true // ZWSP..RLM
    in 0x202A..0x202E -> true // Bidi overrides
    in 0x2060..0x206F -> true // Word joiner etc
    in 0xFE00..0xFE0F -> true // Variation selectors 1-16
    in 0xFFF0..0xFFF8 -> true
    in 0x1BCA0..0x1BCA3 -> true
    in 0x1D173..0x1D17A -> true
    in 0xE0000..0xE0FFF -> true // Tag characters + VS-17..256
    else -> false
}

// ────────────────────────────────────────────────────────────────────────────
// Emoji heuristics.
// ────────────────────────────────────────────────────────────────────────────

/** True if [cp] is in a code-point block likely to produce an emoji. */
private fun isLikelyEmojiCodePoint(cp: Int): Boolean {
    return cp in 0x1F000..0x1FBFF ||
        cp in 0x2300..0x23FF ||
        cp in 0x2600..0x27BF ||
        cp in 0x2B50..0x2B55
}

/**
 * Is [segment] — a grapheme cluster — an emoji?
 *
 * Returns true when:
 * - The first code point is in a known emoji block, AND the cluster is a
 *   single grapheme (which the segmenter guarantees).
 * - OR the cluster contains a ZWJ (joiner) between valid emoji parts.
 * - OR the cluster contains VS16 (U+FE0F), which requests emoji presentation.
 *
 * False negatives here are acceptable (we'll fall through to the East Asian
 * Width branch), but false positives may over-report width.
 */
private fun isEmojiGrapheme(segment: String): Boolean {
    if (segment.isEmpty()) return false
    val firstCp = segment.codePointAt(0)

    // Regional indicator pairs form flag emojis.
    if (firstCp in 0x1F1E6..0x1F1FF) return true

    // Single code point from known emoji blocks.
    if (isLikelyEmojiCodePoint(firstCp)) return true

    // Multi-code-point clusters with an ZWJ or VS16 are emoji sequences.
    if (segment.length > 2) {
        if (segment.contains('\u200D') || segment.contains('\uFE0F')) {
            // Require at least one code point to be in an emoji block to
            // reduce false positives on e.g. arabic letter sequences.
            var i = 0
            while (i < segment.length) {
                val cp = segment.codePointAt(i)
                if (isLikelyEmojiCodePoint(cp) || cp in 0x1F1E6..0x1F1FF) return true
                i += Character.charCount(cp)
            }
        }
    } else if (segment.contains('\uFE0F')) {
        return true
    }

    return false
}

// ────────────────────────────────────────────────────────────────────────────
// Grapheme width.
// ────────────────────────────────────────────────────────────────────────────

/**
 * Width, in terminal cells, of one grapheme cluster [segment].
 *
 * Algorithm:
 *  1. If the entire cluster is zero-width code points, it contributes 0.
 *  2. If it looks like an emoji (RGI or streaming intermediate), it's width 2.
 *  3. Strip leading non-printing code points, then consult the East Asian
 *     Width table on the first remaining code point.
 *  4. Regional indicators (flag halves) are always width 2 even in isolation.
 */
private fun graphemeWidth(segment: String): Int {
    if (segment.isEmpty()) return 0

    // Zero-width cluster fast path.
    if (isZeroWidthCluster(segment)) return 0

    if (isEmojiGrapheme(segment)) return 2

    // Strip leading non-printing code points to find the base visible cp.
    var i = 0
    while (i < segment.length) {
        val cp = segment.codePointAt(i)
        if (!isLeadingNonPrinting(cp)) break
        i += Character.charCount(cp)
    }
    if (i >= segment.length) return 0

    val baseCp = segment.codePointAt(i)

    // Regional indicator singletons (streaming pre-pair) must be width 2.
    if (baseCp in 0x1F1E6..0x1F1FF) return 2

    var width = eastAsianWidth(baseCp)

    // Trailing Halfwidth/Fullwidth Forms — rule for e.g. "ｶﾞ".
    if (segment.length > Character.charCount(baseCp)) {
        var j = i + Character.charCount(baseCp)
        while (j < segment.length) {
            val cp = segment.codePointAt(j)
            if (cp in 0xFF00..0xFFEF) width += eastAsianWidth(cp)
            j += Character.charCount(cp)
        }
    }

    return width
}

/** True if every code point in [segment] is zero-width. */
private fun isZeroWidthCluster(segment: String): Boolean {
    var i = 0
    while (i < segment.length) {
        val cp = segment.codePointAt(i)
        if (!isZeroWidthCp(cp)) return false
        i += Character.charCount(cp)
    }
    return true
}

/** Non-printing at the head of a grapheme — strip these before measuring. */
private fun isLeadingNonPrinting(cp: Int): Boolean {
    // Same set as Zero-Width plus Format is already covered.
    return isZeroWidthCp(cp)
}

// ────────────────────────────────────────────────────────────────────────────
// Grapheme segmentation. JVM's BreakIterator gives us Unicode grapheme
// clusters — not as accurate as ICU's RGI_Emoji-aware segmenter, so we post-
// process ZWJ-chained graphemes manually.
// ────────────────────────────────────────────────────────────────────────────

/**
 * Split [text] into grapheme clusters. The iterator merges adjacent clusters
 * separated by ZWJ (U+200D) or VS16 (U+FE0F) — BreakIterator on recent JDKs
 * already handles most ZWJ cases, but we coalesce to be safe across JDK
 * versions.
 */
private fun segmentGraphemes(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val it = BreakIterator.getCharacterInstance()
    it.setText(text)
    val out = mutableListOf<String>()
    var start = it.first()
    var end = it.next()
    while (end != BreakIterator.DONE) {
        out += text.substring(start, end)
        start = end
        end = it.next()
    }
    return coalesceZwj(out)
}

/**
 * Merge adjacent grapheme clusters that form a single ZWJ emoji sequence.
 * JDK < 17 BreakIterator under-segments here; we fix it by gluing a cluster
 * to its predecessor when:
 *  - Previous cluster ends in ZWJ (U+200D), OR
 *  - Current cluster starts with ZWJ, OR
 *  - Current cluster is a standalone VS16 (U+FE0F).
 */
private fun coalesceZwj(clusters: List<String>): List<String> {
    if (clusters.size <= 1) return clusters
    val out = ArrayList<String>(clusters.size)
    for (cluster in clusters) {
        val last = out.lastOrNull()
        if (last == null) {
            out += cluster
            continue
        }
        val lastEndsZwj = last.endsWith('\u200D')
        val startsZwj = cluster.startsWith('\u200D')
        val isLoneVs16 = cluster == "\uFE0F"
        if (lastEndsZwj || startsZwj || isLoneVs16) {
            out[out.size - 1] = last + cluster
        } else {
            out += cluster
        }
    }
    return out
}

// ────────────────────────────────────────────────────────────────────────────
// ANSI escape extraction.
// ────────────────────────────────────────────────────────────────────────────

private data class AnsiMatch(val code: String, val length: Int)

private fun extractAnsiCode(text: String, pos: Int): AnsiMatch? {
    if (pos >= text.length || text[pos] != '\u001b') return null
    if (pos + 1 >= text.length) return null

    return when (text[pos + 1]) {
        '[' -> extractCsi(text, pos)
        ']' -> extractOscOrApc(text, pos)
        '_' -> extractOscOrApc(text, pos)
        else -> null
    }
}

private fun extractCsi(text: String, pos: Int): AnsiMatch? {
    var j = pos + 2
    while (j < text.length) {
        val c = text[j]
        if (c == 'm' || c == 'G' || c == 'K' || c == 'H' || c == 'J') {
            return AnsiMatch(text.substring(pos, j + 1), j + 1 - pos)
        }
        j++
    }
    return null
}

private fun extractOscOrApc(text: String, pos: Int): AnsiMatch? {
    var j = pos + 2
    while (j < text.length) {
        val c = text[j]
        if (c == '\u0007') { // BEL
            return AnsiMatch(text.substring(pos, j + 1), j + 1 - pos)
        }
        if (c == '\u001b' && j + 1 < text.length && text[j + 1] == '\\') {
            return AnsiMatch(text.substring(pos, j + 2), j + 2 - pos)
        }
        j++
    }
    return null
}

// ────────────────────────────────────────────────────────────────────────────
// Active SGR tracking — preserves styling across line/segment boundaries.
// ────────────────────────────────────────────────────────────────────────────

private class AnsiCodeTracker {
    var bold = false
    var dim = false
    var italic = false
    var underline = false
    var blink = false
    var inverse = false
    var hidden = false
    var strikethrough = false
    var fgColor: String? = null
    var bgColor: String? = null

    private val sgrParamsRegex = Regex("\\u001b\\[([\\d;]*)m")

    fun process(code: String) {
        if (!code.endsWith('m')) return
        val match = sgrParamsRegex.matchEntire(code) ?: return
        val params = match.groupValues[1]
        if (params.isEmpty() || params == "0") {
            reset()
            return
        }
        val parts = params.split(';')
        var i = 0
        while (i < parts.size) {
            val c = parts[i].toIntOrNull() ?: run { i++; continue }

            if (c == 38 || c == 48) {
                // Extended color forms: 38;5;N, 38;2;R;G;B
                val next = parts.getOrNull(i + 1)
                if (next == "5" && parts.getOrNull(i + 2) != null) {
                    val colorCode = "${parts[i]};${parts[i + 1]};${parts[i + 2]}"
                    if (c == 38) fgColor = colorCode else bgColor = colorCode
                    i += 3
                    continue
                } else if (next == "2" && parts.getOrNull(i + 4) != null) {
                    val colorCode = listOf(parts[i], parts[i + 1], parts[i + 2], parts[i + 3], parts[i + 4])
                        .joinToString(";")
                    if (c == 38) fgColor = colorCode else bgColor = colorCode
                    i += 5
                    continue
                }
            }

            when (c) {
                0 -> reset()
                1 -> bold = true
                2 -> dim = true
                3 -> italic = true
                4 -> underline = true
                5 -> blink = true
                7 -> inverse = true
                8 -> hidden = true
                9 -> strikethrough = true
                21 -> bold = false
                22 -> { bold = false; dim = false }
                23 -> italic = false
                24 -> underline = false
                25 -> blink = false
                27 -> inverse = false
                28 -> hidden = false
                29 -> strikethrough = false
                39 -> fgColor = null
                49 -> bgColor = null
                else -> {
                    if (c in 30..37 || c in 90..97) fgColor = c.toString()
                    else if (c in 40..47 || c in 100..107) bgColor = c.toString()
                }
            }
            i++
        }
    }

    fun reset() {
        bold = false; dim = false; italic = false
        underline = false; blink = false; inverse = false
        hidden = false; strikethrough = false
        fgColor = null; bgColor = null
    }

    fun clear() = reset()

    fun hasActiveCodes(): Boolean =
        bold || dim || italic || underline || blink || inverse ||
            hidden || strikethrough || fgColor != null || bgColor != null

    fun getActiveCodes(): String {
        if (!hasActiveCodes()) return ""
        val codes = mutableListOf<String>()
        if (bold) codes += "1"
        if (dim) codes += "2"
        if (italic) codes += "3"
        if (underline) codes += "4"
        if (blink) codes += "5"
        if (inverse) codes += "7"
        if (hidden) codes += "8"
        if (strikethrough) codes += "9"
        fgColor?.let { codes += it }
        bgColor?.let { codes += it }
        return "\u001b[${codes.joinToString(";")}m"
    }

    fun getLineEndReset(): String =
        if (underline) "\u001b[24m" else ""
}

private fun updateTrackerFromText(text: String, tracker: AnsiCodeTracker) {
    var i = 0
    while (i < text.length) {
        val ansi = extractAnsiCode(text, i)
        if (ansi != null) {
            tracker.process(ansi.code)
            i += ansi.length
        } else {
            i++
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// visibleWidth
// ────────────────────────────────────────────────────────────────────────────

private fun isPrintableAscii(str: String): Boolean {
    for (ch in str) {
        val code = ch.code
        if (code < 0x20 || code > 0x7E) return false
    }
    return true
}

/**
 * Measure the visible width (in terminal cells) of [text].
 *
 * Skips ANSI escape sequences. Counts East Asian wide characters as 2 cells.
 * Handles emoji ZWJ sequences and variation selectors as single graphemes.
 *
 * Invariant: `visibleWidth(text) <= text.length` for all well-formed input.
 */
fun visibleWidth(text: String): Int {
    if (text.isEmpty()) return 0
    if (isPrintableAscii(text)) return text.length

    // Normalize: tabs → 3 spaces; strip all recognized escapes.
    var clean = if (text.indexOf('\t') >= 0) text.replace("\t", "   ") else text
    if (clean.indexOf('\u001b') >= 0) clean = stripAnsiInternal(clean)

    var width = 0
    for (seg in segmentGraphemes(clean)) {
        width += graphemeWidth(seg)
    }
    return width
}

// ────────────────────────────────────────────────────────────────────────────
// stripAnsi
// ────────────────────────────────────────────────────────────────────────────

/** Internal version — callable without `stripAnsi`'s fast path. */
private fun stripAnsiInternal(text: String): String {
    if (text.indexOf('\u001b') < 0) return text
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val m = extractAnsiCode(text, i)
        if (m != null) {
            i += m.length
        } else {
            sb.append(text[i])
            i++
        }
    }
    return sb.toString()
}

/**
 * Strip all ANSI CSI, OSC, and DCS escape sequences from [text].
 * Returns a string whose [String.length] equals its visible width (modulo
 * wide-char counting, which this does NOT adjust — use [visibleWidth] for
 * cell counting).
 */
fun stripAnsi(text: String): String = stripAnsiInternal(text)

// ────────────────────────────────────────────────────────────────────────────
// truncateToWidth
// ────────────────────────────────────────────────────────────────────────────

/**
 * Truncate [text] so its visible width is at most [max] cells, appending
 * [ellipsis] (visible width 1) if truncation occurred.
 *
 * Preserves ANSI styling: any `\u001b[…m` SGR state entered by the
 * truncated portion is terminated with `\u001b[0m` in the returned string.
 *
 * If [max] is 0 or negative, returns the empty string.
 */
fun truncateToWidth(text: String, max: Int, ellipsis: String = "…"): String {
    if (max <= 0) return ""
    if (text.isEmpty()) return ""

    val ellipsisWidth = visibleWidth(ellipsis)

    // Ellipsis won't fit at all.
    if (ellipsisWidth >= max) {
        val textWidth = visibleWidth(text)
        if (textWidth <= max) return text
        // Attempt to fit the ellipsis itself by clipping.
        val clipped = truncateFragmentToWidth(ellipsis, max)
        if (clipped.width == 0) return ""
        return finalizeTruncatedResult("", clipped.text)
    }

    // Pure ASCII fast path.
    if (isPrintableAscii(text)) {
        if (text.length <= max) return text
        val targetWidth = max - ellipsisWidth
        return finalizeTruncatedResult(text.substring(0, targetWidth), ellipsis)
    }

    val targetWidth = max - ellipsisWidth
    val result = StringBuilder()
    var pendingAnsi = StringBuilder()
    var visibleSoFar = 0
    var keptWidth = 0
    var keepContiguousPrefix = true
    var overflowed = false
    var exhaustedInput = false

    val hasAnsi = text.indexOf('\u001b') >= 0
    val hasTabs = text.indexOf('\t') >= 0

    if (!hasAnsi && !hasTabs) {
        for (segment in segmentGraphemes(text)) {
            val w = graphemeWidth(segment)
            if (keepContiguousPrefix && keptWidth + w <= targetWidth) {
                result.append(segment)
                keptWidth += w
            } else {
                keepContiguousPrefix = false
            }
            visibleSoFar += w
            if (visibleSoFar > max) {
                overflowed = true
                break
            }
        }
        exhaustedInput = !overflowed
    } else {
        var i = 0
        while (i < text.length) {
            val ansi = extractAnsiCode(text, i)
            if (ansi != null) {
                pendingAnsi.append(ansi.code)
                i += ansi.length
                continue
            }

            if (text[i] == '\t') {
                if (keepContiguousPrefix && keptWidth + 3 <= targetWidth) {
                    if (pendingAnsi.isNotEmpty()) {
                        result.append(pendingAnsi)
                        pendingAnsi = StringBuilder()
                    }
                    result.append('\t')
                    keptWidth += 3
                } else {
                    keepContiguousPrefix = false
                    pendingAnsi = StringBuilder()
                }
                visibleSoFar += 3
                if (visibleSoFar > max) {
                    overflowed = true
                    break
                }
                i++
                continue
            }

            // Scan forward to next escape or tab.
            var end = i
            while (end < text.length && text[end] != '\t') {
                if (extractAnsiCode(text, end) != null) break
                end++
            }

            val chunk = text.substring(i, end)
            for (segment in segmentGraphemes(chunk)) {
                val w = graphemeWidth(segment)
                if (keepContiguousPrefix && keptWidth + w <= targetWidth) {
                    if (pendingAnsi.isNotEmpty()) {
                        result.append(pendingAnsi)
                        pendingAnsi = StringBuilder()
                    }
                    result.append(segment)
                    keptWidth += w
                } else {
                    keepContiguousPrefix = false
                    pendingAnsi = StringBuilder()
                }
                visibleSoFar += w
                if (visibleSoFar > max) {
                    overflowed = true
                    break
                }
            }
            if (overflowed) break
            i = end
        }
        exhaustedInput = i >= text.length
    }

    if (!overflowed && exhaustedInput) return text

    return finalizeTruncatedResult(result.toString(), ellipsis)
}

private data class TruncatedFragment(val text: String, val width: Int)

private fun truncateFragmentToWidth(text: String, maxWidth: Int): TruncatedFragment {
    if (maxWidth <= 0 || text.isEmpty()) return TruncatedFragment("", 0)
    if (isPrintableAscii(text)) {
        val clipped = text.take(maxWidth)
        return TruncatedFragment(clipped, clipped.length)
    }

    val sb = StringBuilder()
    var w = 0
    for (segment in segmentGraphemes(text)) {
        val gw = graphemeWidth(segment)
        if (w + gw > maxWidth) break
        sb.append(segment)
        w += gw
    }
    return TruncatedFragment(sb.toString(), w)
}

private fun finalizeTruncatedResult(prefix: String, ellipsis: String): String {
    val reset = "\u001b[0m"
    return if (ellipsis.isNotEmpty()) {
        "$prefix$reset$ellipsis$reset"
    } else {
        "$prefix$reset"
    }
}

// ────────────────────────────────────────────────────────────────────────────
// hardWrap
//
// Chunk at column N. ANSI state is carried across segments — every
// continuation segment is prefixed with the active SGR codes and (when any
// SGR was active) the previous segment gets a trailing reset.
// ────────────────────────────────────────────────────────────────────────────

/**
 * Hard-wrap [text] into segments whose visible width is at most [width] cells.
 *
 * Does NOT break on word boundaries — this is raw "chunk at column N" wrapping,
 * suitable for rendering code blocks or any content where preserving character
 * positions matters. For word-wrapping, see [wordWrap].
 *
 * ANSI escape sequences inside [text] are preserved across segment boundaries:
 * if a color is active when a segment ends, the segment gets a `\u001b[0m`
 * append and the next segment gets the re-entered SGR prefix.
 *
 * An empty input yields `listOf("")`. Width must be >= 1.
 */
fun hardWrap(text: String, width: Int): List<String> {
    require(width >= 1) { "hardWrap width must be >= 1, was $width" }
    if (text.isEmpty()) return listOf("")

    // Treat literal newlines as hard breaks.
    val inputLines = text.split('\n')
    val out = mutableListOf<String>()
    val tracker = AnsiCodeTracker()

    for ((lineIdx, inputLine) in inputLines.withIndex()) {
        // Carry active styles from previous lines onto the first segment.
        val prefix = if (lineIdx > 0) tracker.getActiveCodes() else ""
        val wrapped = hardWrapSingle(prefix + inputLine, width)
        out += wrapped
        updateTrackerFromText(inputLine, tracker)
    }

    return if (out.isEmpty()) listOf("") else out
}

/**
 * Hard-wrap a single logical line (no embedded \n). Returns at least one
 * segment, each of which has `visibleWidth(segment) <= width`.
 */
private fun hardWrapSingle(line: String, width: Int): List<String> {
    if (line.isEmpty()) return listOf("")
    if (visibleWidth(line) <= width) return listOf(line)

    val tracker = AnsiCodeTracker()
    val out = mutableListOf<String>()
    var currentLine = StringBuilder()
    var currentWidth = 0
    var needReset = false // Whether current line has any ANSI that must be closed

    // Walk the string, classifying each chunk as ANSI or a grapheme.
    val segments = splitIntoAnsiAndGraphemes(line)
    for (seg in segments) {
        if (seg.isAnsi) {
            currentLine.append(seg.value)
            tracker.process(seg.value)
            if (tracker.hasActiveCodes()) needReset = true
            continue
        }
        val g = seg.value
        if (g.isEmpty()) continue
        val gw = graphemeWidth(g)
        if (currentWidth + gw > width) {
            // Close current segment.
            if (needReset) currentLine.append("\u001b[0m")
            out += currentLine.toString()
            // Start new segment with active styling re-applied.
            currentLine = StringBuilder()
            val active = tracker.getActiveCodes()
            if (active.isNotEmpty()) {
                currentLine.append(active)
                needReset = true
            } else {
                needReset = false
            }
            currentWidth = 0
        }
        currentLine.append(g)
        currentWidth += gw
    }

    if (currentLine.isNotEmpty() || out.isEmpty()) {
        if (needReset) currentLine.append("\u001b[0m")
        out += currentLine.toString()
    }

    return out
}

private class AnsiOrGrapheme(val isAnsi: Boolean, val value: String)

/**
 * Walk [text] emitting ANSI escape codes and grapheme clusters as distinct
 * tokens. Used by the wrappers to attribute width to graphemes only.
 */
private fun splitIntoAnsiAndGraphemes(text: String): List<AnsiOrGrapheme> {
    val out = mutableListOf<AnsiOrGrapheme>()
    var i = 0
    while (i < text.length) {
        val ansi = extractAnsiCode(text, i)
        if (ansi != null) {
            out += AnsiOrGrapheme(isAnsi = true, value = ansi.code)
            i += ansi.length
            continue
        }
        // Accumulate until the next ANSI code, then grapheme-segment the run.
        var end = i
        while (end < text.length && extractAnsiCode(text, end) == null) end++
        val chunk = text.substring(i, end)
        for (g in segmentGraphemes(chunk)) {
            out += AnsiOrGrapheme(isAnsi = false, value = g)
        }
        i = end
    }
    return out
}

// ────────────────────────────────────────────────────────────────────────────
// wordWrap
//
// Word-boundary wrap. Falls back to hardWrap for words that are individually
// too long. ANSI styling is preserved across segments.
// ────────────────────────────────────────────────────────────────────────────

/**
 * Word-wrap [text] to fit within [width] cells per line.
 *
 * Breaks at whitespace where possible; falls back to [hardWrap] for
 * single-word overflow (e.g. a long URL). Preserves ANSI styling.
 *
 * Width must be >= 1.
 */
fun wordWrap(text: String, width: Int): List<String> {
    require(width >= 1) { "wordWrap width must be >= 1, was $width" }
    if (text.isEmpty()) return listOf("")

    val inputLines = text.split('\n')
    val out = mutableListOf<String>()
    val tracker = AnsiCodeTracker()
    for ((lineIdx, inputLine) in inputLines.withIndex()) {
        val prefix = if (lineIdx > 0) tracker.getActiveCodes() else ""
        out += wordWrapSingle(prefix + inputLine, width)
        updateTrackerFromText(inputLine, tracker)
    }
    return if (out.isEmpty()) listOf("") else out
}

private fun wordWrapSingle(line: String, width: Int): List<String> {
    if (line.isEmpty()) return listOf("")
    if (visibleWidth(line) <= width) return listOf(line)

    val tokens = splitIntoTokensWithAnsi(line)
    val wrapped = mutableListOf<String>()
    val tracker = AnsiCodeTracker()
    var currentLine = StringBuilder()
    var currentWidth = 0

    for (token in tokens) {
        val tokenWidth = visibleWidth(token)
        val isWhitespace = token.isNotEmpty() && token.all { it == ' ' || it == '\t' }

        // Token itself wider than the wrap width → fall back to hard wrap.
        if (tokenWidth > width && !isWhitespace) {
            if (currentLine.isNotEmpty()) {
                wrapped += currentLine.toString().trimEnd()
                currentLine = StringBuilder()
                currentWidth = 0
            }
            // Break this long token character-by-character with style state.
            val broken = hardWrapSingle(tracker.getActiveCodes() + token, width)
            // All but the last go out directly; the last becomes our current buffer.
            for (idx in 0 until broken.size - 1) wrapped += broken[idx]
            currentLine.append(broken.last())
            currentWidth = visibleWidth(broken.last())
            updateTrackerFromText(token, tracker)
            continue
        }

        val totalNeeded = currentWidth + tokenWidth
        if (totalNeeded > width && currentWidth > 0) {
            // Trim trailing whitespace before wrapping.
            wrapped += currentLine.toString().trimEnd()
            if (isWhitespace) {
                // Skip whitespace at the start of a new line.
                currentLine = StringBuilder(tracker.getActiveCodes())
                currentWidth = 0
            } else {
                currentLine = StringBuilder(tracker.getActiveCodes())
                currentLine.append(token)
                currentWidth = tokenWidth
            }
        } else {
            currentLine.append(token)
            currentWidth += tokenWidth
        }
        updateTrackerFromText(token, tracker)
    }

    if (currentLine.isNotEmpty()) {
        wrapped += currentLine.toString().trimEnd()
    }

    return if (wrapped.isEmpty()) listOf("") else wrapped
}

/**
 * Tokenize [text] preserving whitespace as its own tokens, with ANSI codes
 * merged into the next visible token (or the last one if trailing).
 */
private fun splitIntoTokensWithAnsi(text: String): List<String> {
    val tokens = mutableListOf<String>()
    var current = StringBuilder()
    var pendingAnsi = StringBuilder()
    var inWhitespace = false
    var i = 0
    while (i < text.length) {
        val ansi = extractAnsiCode(text, i)
        if (ansi != null) {
            pendingAnsi.append(ansi.code)
            i += ansi.length
            continue
        }
        val ch = text[i]
        val isSpace = ch == ' '

        if (isSpace != inWhitespace && current.isNotEmpty()) {
            tokens += current.toString()
            current = StringBuilder()
        }
        if (pendingAnsi.isNotEmpty()) {
            current.append(pendingAnsi)
            pendingAnsi = StringBuilder()
        }
        inWhitespace = isSpace
        current.append(ch)
        i++
    }
    if (pendingAnsi.isNotEmpty()) current.append(pendingAnsi)
    if (current.isNotEmpty()) tokens += current.toString()
    return tokens
}

// ────────────────────────────────────────────────────────────────────────────
// sliceByColumn
// ────────────────────────────────────────────────────────────────────────────

/**
 * Slice [text] by visible column range `[start, end)`, preserving ANSI styling
 * across the cut. Used by overlay compositing where a slice of one line is
 * spliced into the middle of another.
 *
 * [strict] = true drops any wide character that straddles a boundary; [strict]
 * = false includes partial wide chars as the full width (may overflow by 1).
 */
fun sliceByColumn(text: String, start: Int, end: Int, strict: Boolean = true): String {
    if (end <= start) return ""
    val clampedStart = start.coerceAtLeast(0)

    val result = StringBuilder()
    var currentCol = 0
    var i = 0
    var pendingAnsi = StringBuilder()

    while (i < text.length) {
        val ansi = extractAnsiCode(text, i)
        if (ansi != null) {
            if (currentCol in clampedStart until end) {
                result.append(ansi.code)
            } else if (currentCol < clampedStart) {
                pendingAnsi.append(ansi.code)
            }
            i += ansi.length
            continue
        }
        // Non-ANSI run up to the next escape.
        var textEnd = i
        while (textEnd < text.length && extractAnsiCode(text, textEnd) == null) textEnd++
        val chunk = text.substring(i, textEnd)

        for (segment in segmentGraphemes(chunk)) {
            val w = graphemeWidth(segment)
            val inRange = currentCol >= clampedStart && currentCol < end
            val fits = !strict || currentCol + w <= end
            if (inRange && fits) {
                if (pendingAnsi.isNotEmpty()) {
                    result.append(pendingAnsi)
                    pendingAnsi = StringBuilder()
                }
                result.append(segment)
            }
            currentCol += w
            if (currentCol >= end) break
        }
        i = textEnd
        if (currentCol >= end) break
    }
    return result.toString()
}

// ────────────────────────────────────────────────────────────────────────────
// Padding
// ────────────────────────────────────────────────────────────────────────────

/**
 * Pad [text] on the right with spaces so its visible width equals [width].
 * If [text] is already wider than [width], [text] is returned unchanged.
 */
fun padRightToWidth(text: String, width: Int): String {
    val current = visibleWidth(text)
    if (current >= width) return text
    val pad = " ".repeat(width - current)
    return text + pad
}

/**
 * Pad [text] on the left with spaces so its visible width equals [width].
 * If [text] is already wider than [width], [text] is returned unchanged.
 */
fun padLeftToWidth(text: String, width: Int): String {
    val current = visibleWidth(text)
    if (current >= width) return text
    val pad = " ".repeat(width - current)
    return pad + text
}
