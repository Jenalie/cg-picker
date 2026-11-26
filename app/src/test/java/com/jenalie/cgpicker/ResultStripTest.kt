package com.jenalie.cgpicker

import com.jenalie.cgpicker.GameColor.*
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResultStripTest {

    /** Half-size Casino Plus screenshots with everything except the results strip blacked out. */
    private fun screen(name: String): Pixels {
        val img: BufferedImage = ImageIO.read(javaClass.classLoader!!.getResource("screens/$name.png"))
        return object : Pixels {
            override val width = img.width
            override val height = img.height
            override fun rgb(x: Int, y: Int) = img.getRGB(x, y) and 0xFFFFFF
        }
    }

    private fun r(a: GameColor, b: GameColor, c: GameColor) = Round(listOf(a, b, c))

    private val bettingStrip = listOf(
        r(YELLOW, BLUE, WHITE),
        r(WHITE, RED, BLUE),
        r(GREEN, PINK, GREEN),
        r(GREEN, GREEN, RED),
        r(BLUE, WHITE, PINK),
        r(BLUE, BLUE, RED),
        r(PINK, RED, YELLOW),
    )

    @Test fun readsTheStripOnTheBettingScreen() {
        assertEquals(bettingStrip, ResultStripReader.read(screen("betting")))
    }

    @Test fun noStripWhileDiceAreRolling() {
        assertNull(ResultStripReader.read(screen("rolling1")))
        assertNull(ResultStripReader.read(screen("rolling2")))
    }

    // ---- tracker ----

    private val a = r(RED, RED, RED)
    private val b = r(BLUE, RED, GREEN)
    private val c = r(PINK, PINK, WHITE)
    private val d = r(YELLOW, GREEN, BLUE)
    private val e = r(WHITE, WHITE, RED)
    private val f = r(GREEN, GREEN, GREEN)

    private fun StripTracker.feed(strip: List<Round>?): List<Round> {
        update(strip)
        return update(strip) // counts once seen twice in a row
    }

    @Test fun needsTwoMatchingReads() {
        val t = StripTracker()
        assertEquals(emptyList<Round>(), t.update(listOf(a, b, c, d)))
        assertEquals(listOf(a, b, c, d), t.update(listOf(a, b, c, d)))
        assertEquals(emptyList<Round>(), t.update(listOf(a, b, c, d)))
    }

    @Test fun learnsNewestOnTheRight() {
        val t = StripTracker()
        t.feed(listOf(a, b, c, d))
        assertEquals(emptyList<Round>(), t.feed(null))
        assertEquals(listOf(e), t.feed(listOf(b, c, d, e)))
        assertEquals(1, t.direction)
        assertEquals(listOf(f), t.feed(listOf(c, d, e, f)))
    }

    @Test fun learnsNewestOnTheLeft() {
        val t = StripTracker()
        t.feed(listOf(d, c, b, a))
        assertEquals(listOf(e), t.feed(listOf(e, d, c, b)))
        assertEquals(-1, t.direction)
        assertEquals(listOf(f), t.feed(listOf(f, e, d, c)))
    }

    @Test fun handlesTwoNewRoundsAndAGrowingStrip() {
        val t = StripTracker(direction = 1, lastStrip = listOf(a, b, c))
        assertEquals(listOf(d, e), t.feed(listOf(a, b, c, d, e)))
    }

    @Test fun noOverlapTakesTheWholeStrip() {
        val t = StripTracker(direction = 1, lastStrip = listOf(a, b, c))
        assertEquals(listOf(d, e, f), t.feed(listOf(d, e, f)))
    }

    @Test fun sameStripAddsNothing() {
        val t = StripTracker(direction = 1, lastStrip = listOf(a, b, c, d))
        assertEquals(emptyList<Round>(), t.feed(listOf(a, b, c, d)))
    }
}
