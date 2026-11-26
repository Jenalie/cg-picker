package com.jenalie.cgpicker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val Bg = Color(0xFF4A0A10)
private val Panel = Color(0xFF6E1420)
private val PanelDark = Color(0xFF35060B)
private val Gold = Color(0xFFFFC83D)
private val Muted = Color(0xFFE8C9CC)

private fun GameColor.ui() = Color(argb)
private fun GameColor.onColor() = if (darkText) Color(0xFF222222) else Color.White
private fun pct(x: Double) = "${(x * 100).roundToInt()}%"

class MainActivity : ComponentActivity() {

    private var waitingForOverlayPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val openTracker = intent.getBooleanExtra(EXTRA_OPEN_TRACKER, false)

        // Launching the app = start the floating picker over whatever is on screen, then get out of the way.
        if (!openTracker && Settings.canDrawOverlays(this)) {
            startFloatingPicker()
            return
        }

        val prefs = getSharedPreferences("cg_picker", Context.MODE_PRIVATE)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Gold, background = Bg, surface = Panel)) {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    var showTracker by remember { mutableStateOf(openTracker) }
                    if (showTracker) {
                        PickerScreen(
                            initialHistory = ColorStats.decode(prefs.getString(KEY_HISTORY, null)),
                            initialWindow = prefs.getInt(KEY_WINDOW, 20),
                            initialShowTop = prefs.getBoolean(KEY_SHOW_TOP, true),
                            onHistoryChanged = { prefs.edit().putString(KEY_HISTORY, ColorStats.encode(it)).apply() },
                            onWindowChanged = { prefs.edit().putInt(KEY_WINDOW, it).apply() },
                            onShowTopChanged = { prefs.edit().putBoolean(KEY_SHOW_TOP, it).apply() },
                        )
                    } else {
                        OverlayPermissionScreen(
                            onAllow = {
                                waitingForOverlayPermission = true
                                startActivity(
                                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                                )
                            },
                            onOpenTracker = { showTracker = true },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the "Display over other apps" settings screen.
        if (waitingForOverlayPermission && Settings.canDrawOverlays(this)) {
            waitingForOverlayPermission = false
            startFloatingPicker()
        }
    }

    private fun startFloatingPicker() {
        OverlayService.start(this)
        finish()
    }

    companion object {
        /** Open the full tracker instead of starting the floating picker. */
        const val EXTRA_OPEN_TRACKER = "open_tracker"
        private const val KEY_HISTORY = "history"
        private const val KEY_WINDOW = "window"
        private const val KEY_SHOW_TOP = "show_top"
    }
}

@Composable
private fun OverlayPermissionScreen(onAllow: () -> Unit, onOpenTracker: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("CG Picker", color = Gold, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(14.dp))
        Text(
            "To float on top of the Color Game, CG Picker needs permission to display over other apps.",
            color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "On the next screen, turn on \"Allow display over other apps\" for CG Picker, then come back.",
            color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = onAllow,
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF3A0008)),
        ) { Text("Allow floating picker", fontWeight = FontWeight.Bold) }
        TextButton(onClick = onOpenTracker) { Text("Open tracker instead", color = Muted) }
    }
}

private val WINDOWS = listOf(10, 20, 50, 0) // 0 = all

@Composable
fun PickerScreen(
    initialHistory: List<Round>,
    initialWindow: Int,
    initialShowTop: Boolean,
    onHistoryChanged: (List<Round>) -> Unit,
    onWindowChanged: (Int) -> Unit,
    onShowTopChanged: (Boolean) -> Unit,
) {
    val history = remember { mutableStateListOf<Round>().apply { addAll(initialHistory) } }
    val pending = remember { mutableStateListOf<GameColor>() }
    var window by remember { mutableStateOf(initialWindow) }
    var showTop by remember { mutableStateOf(initialShowTop) }
    var confirmReset by remember { mutableStateOf(false) }

    val stats = ColorStats.compute(history, window)
    val top = if (history.isEmpty()) emptyList() else ColorStats.topColors(stats, 3)
    val windowSize = if (window == 0) history.size else minOf(window, history.size)

    fun commitHistory() = onHistoryChanged(history.toList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CG Picker", color = Gold, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Text("Color Game tracker", color = Muted, fontSize = 13.sp)
            }
            Text(
                "${history.size} rounds",
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Panel)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // Top 3 toggle card
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Colors to bet on", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Picked from the most frequent colors in the last $windowSize rounds",
                        color = Muted, fontSize = 12.sp,
                    )
                }
                Switch(
                    checked = showTop,
                    onCheckedChange = { showTop = it; onShowTopChanged(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF2DBE3C),
                    ),
                )
            }
            if (showTop) {
                Spacer(Modifier.height(12.dp))
                if (top.isEmpty()) {
                    Text(
                        "Add a few rounds below to see the top colors.",
                        color = Muted, fontSize = 14.sp,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        top.forEachIndexed { i, color ->
                            val s = stats.first { it.color == color }
                            TopChip(rank = i + 1, color = color, recentHitRate = s.hitRate, modifier = Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "If you bet the same amount on all 3:",
                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "At least one wins: ${pct(ColorStats.THREE_PICKS_ANY_HIT)}  •  " +
                            "You end up ahead: ${pct(ColorStats.THREE_PICKS_AHEAD)}  •  " +
                            "Break even: ${pct(ColorStats.THREE_PICKS_EVEN)}  •  " +
                            "You lose money: ${pct(ColorStats.THREE_PICKS_BEHIND)}",
                        color = Muted, fontSize = 12.sp,
                    )
                }
            }
        }

        // Window selector
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Look back:", color = Muted, fontSize = 13.sp)
            WINDOWS.forEach { w ->
                val selected = w == window
                Text(
                    if (w == 0) "All" else "$w",
                    color = if (selected) Color(0xFF3A0008) else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) Gold else Panel)
                        .clickable { window = w; onWindowChanged(w) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }

        // Board (3 x 2, same layout as the game)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GameColor.values().toList().chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { color ->
                        val s = stats.first { it.color == color }
                        val rank = if (showTop) top.indexOf(color).takeIf { it >= 0 }?.plus(1) else null
                        BoardTile(s, rank, Modifier.weight(1f))
                    }
                }
            }
        }

        // Result entry
        Card {
            Text("Enter last result (3 dice)", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(3) { i ->
                    val c = pending.getOrNull(i)
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(c?.ui() ?: PanelDark)
                            .border(2.dp, if (c == null) Muted.copy(alpha = 0.4f) else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable(enabled = c != null) { pending.removeAt(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (c == null) Text("${i + 1}", color = Muted)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { pending.clear() }, enabled = pending.isNotEmpty()) {
                    Text("Clear", color = if (pending.isNotEmpty()) Muted else Muted.copy(alpha = 0.4f))
                }
            }
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GameColor.values().toList().chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { color ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(color.ui())
                                    .clickable(enabled = pending.size < 3) { pending.add(color) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(color.label, color = color.onColor(), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        history.add(Round(pending.toList()))
                        pending.clear()
                        commitHistory()
                    },
                    enabled = pending.size == 3,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF3A0008)),
                ) { Text("Save round", fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = { history.removeAt(history.lastIndex); commitHistory() },
                    enabled = history.isNotEmpty(),
                    border = BorderStroke(1.dp, Muted),
                ) { Text("Undo", color = Color.White) }
            }
        }

        // History strip (newest first)
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("History", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmReset = true }, enabled = history.isNotEmpty()) {
                    Text("Reset", color = if (history.isNotEmpty()) Muted else Muted.copy(alpha = 0.4f))
                }
            }
            if (history.isEmpty()) {
                Text("No rounds yet.", color = Muted, fontSize = 13.sp)
            } else {
                Spacer(Modifier.height(6.dp))
                val newestFirst = history.asReversed().toList()
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(newestFirst) { _, round ->
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(PanelDark)
                                .padding(4.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            round.dice.forEach { c ->
                                Box(
                                    Modifier
                                        .size(18.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(c.ui())
                                )
                            }
                        }
                    }
                }
            }
        }

        // Honest note
        Text(
            "Heads-up: if the dice are fair, every round is independent. Each color has a " +
                "${pct(ColorStats.FAIR_HIT_RATE)} chance to show on at least one die, no matter " +
                "what happened before. The picks show what has come up most often recently. " +
                "It is not a guarantee, and no app can predict the next roll. Set a limit and only play with money you can afford to lose.",
            color = Muted.copy(alpha = 0.85f),
            fontSize = 12.sp,
            textAlign = TextAlign.Start,
        )
        Spacer(Modifier.height(8.dp))
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset history?") },
            text = { Text("This deletes all ${history.size} saved rounds.") },
            confirmButton = {
                TextButton(onClick = {
                    history.clear(); pending.clear(); commitHistory(); confirmReset = false
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Panel)
            .padding(14.dp),
    ) { content() }
}

@Composable
private fun TopChip(rank: Int, color: GameColor, recentHitRate: Double, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.ui())
            .border(3.dp, Gold, RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("#$rank", color = color.onColor(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(color.label, color = color.onColor(), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        Text("${pct(ColorStats.FAIR_HIT_RATE)} win chance", color = color.onColor(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("recent: ${pct(recentHitRate)}", color = color.onColor(), fontSize = 11.sp)
    }
}

@Composable
private fun BoardTile(s: ColorStat, rank: Int?, modifier: Modifier = Modifier) {
    val highlighted = rank != null
    Box(
        modifier = modifier
            .height(92.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(s.color.ui().copy(alpha = if (rank == null && s.diceCount == 0) 0.55f else 1f))
            .then(if (highlighted) Modifier.border(4.dp, Gold, RoundedCornerShape(12.dp)) else Modifier)
            .padding(8.dp),
    ) {
        val fg = s.color.onColor()
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(pct(s.diceShare), color = fg, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                when (s.roundsSinceSeen) {
                    null -> "not seen"
                    0 -> "hit last round"
                    else -> "${s.roundsSinceSeen} rounds ago"
                },
                color = fg, fontSize = 11.sp,
            )
        }
        if (rank != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Gold),
                contentAlignment = Alignment.Center,
            ) {
                Text("$rank", color = Color(0xFF3A0008), fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            }
        }
    }
}
