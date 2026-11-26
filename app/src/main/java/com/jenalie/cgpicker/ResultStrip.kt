package com.jenalie.cgpicker

import kotlin.math.sqrt

/** Read-only access to a screen image. [rgb] returns 0xRRGGBB. */
interface Pixels {
    val width: Int
    val height: Int
    fun rgb(x: Int, y: Int): Int
}

/**
 * Reads the recent-results strip of the Casino Plus Color Game (bottom of the betting screen):
 * up to 8 slots, each a column of 3 colored squares (one finished round).
 *
 * Positions are fractions of the full screen, measured on a 1256x2760 screenshot, so they work
 * at any capture scale on that screen shape. The strip is only on screen while betting is open;
 * when it is not visible (dice rolling, other screens, something covering it) [read] returns null.
 */
object ResultStripReader {

    const val SLOTS = 8
    private const val SLOT0_X = 0.38654
    private const val SLOT_STEP_X = 0.06602
    private val ROW_Y = doubleArrayOf(0.80236, 0.82083, 0.83922)

    /** Thin dark lines between the squares of a slot: they must be the strip's background color. */
    private val GAP_Y = doubleArrayOf(0.81159, 0.82989)

    /** Half-size of the sampled square, as a fraction of the screen width (the squares are ~3.3% wide). */
    private const val PATCH = 0.005

    private const val COLOR_MATCH = 60.0
    private const val BACKGROUND_MATCH = 35.0

    private val SQUARE_COLORS = mapOf(
        GameColor.YELLOW to 0xFEE43D,
        GameColor.WHITE to 0xECECED,
        GameColor.PINK to 0xEF4B96,
        GameColor.BLUE to 0x4A9FE0,
        GameColor.RED to 0xDE2A20,
        GameColor.GREEN to 0x36B134,
    )
    private const val EMPTY_SLOT = 0x461C21
    private const val STRIP_BACKGROUND = 0x471F25

    /** Rounds shown in the strip, in screen order (left to right), or null if the strip isn't visible. */
    fun read(p: Pixels): List<Round>? {
        val rounds = mutableListOf<Round>()
        var state = 0 // 0 = before filled slots, 1 = in filled slots, 2 = empty slot after filled ones
        for (i in 0 until SLOTS) {
            val fx = SLOT0_X + i * SLOT_STEP_X
            if (GAP_Y.any { distance(sample(p, fx, it, flat = true), STRIP_BACKGROUND) > BACKGROUND_MATCH }) return null

            val cells = ROW_Y.map { sample(p, fx, it, flat = false) }
            val colors = cells.map { classify(it) }
            when {
                colors.all { it != null } -> {
                    if (state == 2) return null
                    state = 1
                    rounds += Round(colors.map { it!! })
                }
                cells.all { distance(it, EMPTY_SLOT) <= BACKGROUND_MATCH } -> {
                    if (state == 1) state = 2
                }
                else -> return null
            }
        }
        return if (rounds.size >= 3) rounds else null
    }

    private fun classify(rgb: Int): GameColor? {
        val best = SQUARE_COLORS.minByOrNull { distance(rgb, it.value) }!!
        return if (distance(rgb, best.value) <= COLOR_MATCH) best.key else null
    }

    /** Per-channel median of a small square (or a 1-pixel-high line when [flat]) around the point. */
    private fun sample(p: Pixels, fx: Double, fy: Double, flat: Boolean): Int {
        val cx = (fx * p.width).toInt()
        val cy = (fy * p.height).toInt()
        val r = maxOf(1, (PATCH * p.width).toInt())
        val ry = if (flat) 0 else r
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        for (x in cx - r..cx + r) for (y in cy - ry..cy + ry) {
            if (x !in 0 until p.width || y !in 0 until p.height) continue
            val c = p.rgb(x, y)
            rs += (c shr 16) and 0xFF; gs += (c shr 8) and 0xFF; bs += c and 0xFF
        }
        if (rs.isEmpty()) return 0
        fun median(v: ArrayList<Int>) = v.sorted()[v.size / 2]
        return (median(rs) shl 16) or (median(gs) shl 8) or median(bs)
    }

    private fun distance(a: Int, b: Int): Double {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toDouble())
    }
}

/**
 * Turns successive reads of the results strip into new rounds, oldest first.
 *
 * A read only counts once the same strip is seen twice in a row (skips animations). When the strip
 * changes, the new rounds are found by matching it against the previous strip. Which end of the strip
 * is newest is learned from the first change; until then it is assumed to be the right end.
 */
class StripTracker(
    /** 1 = newest on the right, -1 = newest on the left, 0 = not known yet. */
    var direction: Int = 0,
    /** Last accepted strip, in screen order. */
    var lastStrip: List<Round>? = null,
) {
    private var candidate: List<Round>? = null

    fun update(strip: List<Round>?): List<Round> {
        if (strip == null) {
            candidate = null
            return emptyList()
        }
        if (strip != candidate) {
            candidate = strip
            return emptyList()
        }
        val prev = lastStrip
        if (prev == strip) return emptyList()
        lastStrip = strip
        if (prev == null) return chronological(strip)

        val right = if (direction != -1) newestOnRight(prev, strip) else null
        val left = if (direction != 1) newestOnLeft(prev, strip) else null
        return when {
            right != null && left == null -> { direction = 1; right }
            left != null && right == null -> { direction = -1; left }
            right != null && left != null -> if (direction == -1) left else right
            // No overlap with the last strip: rounds were missed while it was hidden. Take what is visible.
            else -> chronological(strip)
        }
    }

    private fun chronological(strip: List<Round>) = if (direction == -1) strip.reversed() else strip

    private fun newestOnRight(prev: List<Round>, now: List<Round>): List<Round>? {
        for (l in minOf(prev.size, now.size) downTo MIN_OVERLAP) {
            if (prev.takeLast(l) == now.take(l)) return now.drop(l).ifEmpty { null }
        }
        return null
    }

    private fun newestOnLeft(prev: List<Round>, now: List<Round>): List<Round>? {
        for (l in minOf(prev.size, now.size) downTo MIN_OVERLAP) {
            if (prev.take(l) == now.takeLast(l)) return now.take(now.size - l).reversed().ifEmpty { null }
        }
        return null
    }

    companion object {
        private const val MIN_OVERLAP = 3
    }
}
