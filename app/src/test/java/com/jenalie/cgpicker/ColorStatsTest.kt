package com.jenalie.cgpicker

import com.jenalie.cgpicker.GameColor.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorStatsTest {

    private val history = listOf(
        Round(listOf(YELLOW, WHITE, PINK)),
        Round(listOf(PINK, PINK, RED)),
        Round(listOf(BLUE, PINK, GREEN)),
    )

    @Test fun countsDiceAndRounds() {
        val pink = ColorStats.compute(history, window = 0).first { it.color == PINK }
        assertEquals(4, pink.diceCount)
        assertEquals(3, pink.roundsHit)
        assertEquals(0, pink.roundsSinceSeen)
    }

    @Test fun windowLimitsRounds() {
        val stats = ColorStats.compute(history, window = 1)
        assertEquals(0, stats.first { it.color == YELLOW }.diceCount)
        assertEquals(null, stats.first { it.color == YELLOW }.roundsSinceSeen)
    }

    @Test fun topThreePutsPinkFirst() {
        val top = ColorStats.topColors(ColorStats.compute(history, window = 0))
        assertEquals(PINK, top.first())
        assertEquals(3, top.size)
    }

    @Test fun encodeDecodeRoundTrip() {
        assertEquals(history, ColorStats.decode(ColorStats.encode(history)))
    }
}
