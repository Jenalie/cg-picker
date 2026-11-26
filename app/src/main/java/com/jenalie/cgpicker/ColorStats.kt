package com.jenalie.cgpicker

/** The six colors on the Color Game dice, in the same order as the betting board. */
enum class GameColor(val label: String, val argb: Long, val darkText: Boolean) {
    YELLOW("Yellow", 0xFFFFD600, true),
    WHITE("White", 0xFFF4F4F4, true),
    PINK("Pink", 0xFFFF4FA3, false),
    BLUE("Blue", 0xFF2F8CFF, false),
    RED("Red", 0xFFF2302B, false),
    GREEN("Green", 0xFF2DBE3C, false);
}

/** One round = the three dice that landed. */
data class Round(val dice: List<GameColor>) {
    init { require(dice.size == 3) { "A round has exactly 3 dice" } }
}

data class ColorStat(
    val color: GameColor,
    /** Number of dice showing this color in the window. */
    val diceCount: Int,
    /** Number of rounds in which this color showed on at least one die. */
    val roundsHit: Int,
    /** Share of all dice in the window (0..1). */
    val diceShare: Double,
    /** Share of rounds the color appeared in (0..1). */
    val hitRate: Double,
    /** Rounds since this color last appeared (0 = appeared last round). Null if never in window. */
    val roundsSinceSeen: Int?,
    /** Weighted score used for ranking (recent rounds count more). */
    val score: Double,
)

object ColorStats {

    /** Chance a given color shows on at least one of three fair dice: 1 - (5/6)^3 ≈ 42.1%. */
    const val FAIR_HIT_RATE = 1.0 - (5.0 / 6.0) * (5.0 / 6.0) * (5.0 / 6.0)

    /*
     * Betting 1 unit on each of 3 different colors (1:1 / 2:1 / 3:1 payouts), fair dice:
     * at least one color hits 189/216 = 87.5%; net result ahead 81/216 = 37.5%,
     * even 27/216 = 12.5%, behind 108/216 = 50%. Verified by enumerating all 216 rolls.
     */
    const val THREE_PICKS_ANY_HIT = 189.0 / 216.0
    const val THREE_PICKS_AHEAD = 81.0 / 216.0
    const val THREE_PICKS_EVEN = 27.0 / 216.0
    const val THREE_PICKS_BEHIND = 108.0 / 216.0

    /** Chance a given color shows on one fair die: 1/6 ≈ 16.7%. */
    const val FAIR_DICE_SHARE = 1.0 / 6.0

    /**
     * Computes stats for the most recent [window] rounds ([history] is oldest-first).
     * Ranking score: each die showing the color adds a weight that decays with age
     * (most recent round = 1.0, each older round × [decay]). With decay = 1.0 it is a plain count.
     */
    fun compute(history: List<Round>, window: Int, decay: Double = 0.93): List<ColorStat> {
        val recent = if (window <= 0 || window >= history.size) history else history.takeLast(window)
        val n = recent.size
        val totalDice = n * 3

        return GameColor.values().map { color ->
            var diceCount = 0
            var roundsHit = 0
            var score = 0.0
            var lastSeenIndex = -1
            recent.forEachIndexed { i, round ->
                val c = round.dice.count { it == color }
                if (c > 0) {
                    diceCount += c
                    roundsHit++
                    lastSeenIndex = i
                    val age = n - 1 - i
                    score += c * Math.pow(decay, age.toDouble())
                }
            }
            ColorStat(
                color = color,
                diceCount = diceCount,
                roundsHit = roundsHit,
                diceShare = if (totalDice == 0) 0.0 else diceCount.toDouble() / totalDice,
                hitRate = if (n == 0) 0.0 else roundsHit.toDouble() / n,
                roundsSinceSeen = if (lastSeenIndex < 0) null else n - 1 - lastSeenIndex,
                score = score,
            )
        }
    }

    /** Top [k] colors by weighted score; ties broken by most recently seen, then board order. */
    fun topColors(stats: List<ColorStat>, k: Int = 3): List<GameColor> =
        stats.sortedWith(
            compareByDescending<ColorStat> { it.score }
                .thenBy { it.roundsSinceSeen ?: Int.MAX_VALUE }
                .thenBy { it.color.ordinal }
        ).take(k).map { it.color }

    // ---- persistence helpers: "YWP,BRG,..." ----

    private fun GameColor.code(): Char = label.first()

    private fun fromCode(c: Char): GameColor? = GameColor.values().firstOrNull { it.code() == c }

    fun encode(history: List<Round>): String =
        history.joinToString(",") { r -> r.dice.map { it.code() }.joinToString("") }

    fun decode(s: String?): List<Round> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split(",").mapNotNull { token ->
            val colors = token.mapNotNull { fromCode(it) }
            if (colors.size == 3) Round(colors) else null
        }
    }
}
