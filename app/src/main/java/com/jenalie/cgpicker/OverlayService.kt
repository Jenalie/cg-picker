package com.jenalie.cgpicker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlin.math.abs
import kotlin.math.roundToInt

private val PANEL = 0xFF6E1420.toInt()
private val PANEL_DARK = 0xFF35060B.toInt()
private val GOLD = 0xFFFFC83D.toInt()
private val ON_GOLD = 0xFF3A0008.toInt()
private val MUTED = 0xFFE8C9CC.toInt()
private val DARK_TEXT = 0xFF222222.toInt()
private val LIVE = 0xFF2DBE3C.toInt()
private val PLACEHOLDER = 0xFF7A5A5E.toInt()
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/**
 * Keeps a small floating picker on top of other apps (for example the Color Game).
 * Collapsed, it is a pill showing 3 colors; tap it to open the panel, drag it to move it.
 * Touches outside the floating window go straight to the app underneath.
 *
 * Two modes:
 * - Random picks (default): 3 random colors, no history needed.
 * - Reading the game: with screen capture on, it reads each result from the game's results strip,
 *   saves it to the history (shared with the tracker), and shows the most frequent colors lately.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var container: FrameLayout? = null
    private var expanded = true
    private var picks = ColorStats.quickPicks()

    /** Where the user placed the window; the real position is this, clamped to keep it on screen. */
    private var anchorX = 0
    private var anchorY = 0

    // Reading the game
    private var projecting = false
    private var capture: ScreenCapture? = null
    private var stripTracker: StripTracker? = null
    private var stripVisible = false
    private var history: List<Round> = emptyList()
    private val reading get() = capture != null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START_READING) projecting = true
        // Enter the foreground first: we may have been started with startForegroundService().
        try {
            startInForeground()
        } catch (e: SecurityException) {
            projecting = false
            startInForeground()
        }
        if (action == ACTION_STOP || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (container == null) {
            showOverlay()
        } else if (action == null) {
            // App opened again while already floating: show the panel.
            expanded = true
            render()
        }
        if (action == ACTION_START_READING && intent != null) startReading(intent)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        capture?.release()
        capture = null
        container?.let { if (it.isAttachedToWindow) wm.removeView(it) }
        container = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        place()
    }

    private fun prefs(): SharedPreferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- reading the game ----

    private fun requestReading() {
        startActivity(Intent(this, CaptureConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun startReading(intent: Intent) {
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        capture?.release()
        capture = null
        try {
            val projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(code, requireNotNull(data))
                ?: error("No screen capture")
            val prefs = prefs()
            stripTracker = StripTracker(
                direction = prefs.getInt(KEY_STRIP_DIR, 0),
                lastStrip = ColorStats.decode(prefs.getString(KEY_STRIP_LAST, null)).ifEmpty { null },
            )
            history = ColorStats.decode(prefs.getString(KEY_HISTORY, null))
            stripVisible = false
            val c = ScreenCapture(this, projection, ::onStripRead, ::onCaptureStopped)
            capture = c
            c.start()
            expanded = true
        } catch (e: Exception) {
            capture?.release()
            capture = null
            stripTracker = null
            projecting = false
            startInForeground()
            Toast.makeText(this, "Couldn't start reading the screen", Toast.LENGTH_SHORT).show()
        }
        render()
    }

    private fun stopReading() {
        capture?.release()
        onCaptureStopped()
    }

    private fun onCaptureStopped() {
        capture = null
        stripTracker = null
        stripVisible = false
        projecting = false
        startInForeground()
        render()
    }

    private fun onStripRead(strip: List<Round>?) {
        val tracker = stripTracker ?: return
        val newRounds = tracker.update(strip)
        var changed = (strip != null) != stripVisible
        stripVisible = strip != null
        if (newRounds.isNotEmpty()) {
            val prefs = prefs()
            // Re-read first so rounds entered in the tracker meanwhile are kept.
            history = ColorStats.decode(prefs.getString(KEY_HISTORY, null)) + newRounds
            prefs.edit()
                .putString(KEY_HISTORY, ColorStats.encode(history))
                .putString(KEY_STRIP_LAST, ColorStats.encode(tracker.lastStrip.orEmpty()))
                .putInt(KEY_STRIP_DIR, tracker.direction)
                .apply()
            changed = true
        }
        if (changed) render()
    }

    /** Most frequent colors in the look-back window and the window size, or null if too few rounds yet. */
    private fun hotColors(): Pair<List<ColorStat>, Int>? {
        val window = prefs().getInt(KEY_WINDOW, 20)
        val n = if (window <= 0) history.size else minOf(window, history.size)
        if (n < MIN_ROUNDS) return null
        val stats = ColorStats.compute(history, window)
        return ColorStats.topColors(stats, 3).map { c -> stats.first { it.color == c } } to n
    }

    // ---- window ----

    private fun showOverlay() {
        val prefs = prefs()
        val dm = resources.displayMetrics
        anchorX = prefs.getInt(KEY_X, Int.MAX_VALUE) // default: right edge
        anchorY = prefs.getInt(KEY_Y, dm.heightPixels / 4)

        params = WindowManager.LayoutParams(
            WRAP, WRAP,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = anchorX.coerceAtMost(dm.widthPixels)
            y = anchorY
        }

        val root = FrameLayout(this)
        // Re-clamp whenever the window changes size (bubble <-> panel).
        root.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) place()
        }
        container = root
        render()
        wm.addView(root, params)
    }

    private fun render() {
        val root = container ?: return
        root.removeAllViews()
        root.addView(if (expanded) buildPanel() else buildBubble())
    }

    private fun place() {
        val root = container ?: return
        if (!root.isAttachedToWindow) return
        val dm = resources.displayMetrics
        val x = anchorX.coerceIn(0, maxOf(0, dm.widthPixels - root.width))
        val y = anchorY.coerceIn(0, maxOf(0, dm.heightPixels - root.height))
        if (x != params.x || y != params.y) {
            params.x = x
            params.y = y
            wm.updateViewLayout(root, params)
        }
    }

    private fun savePosition() {
        val root = container ?: return
        val dm = resources.displayMetrics
        // Touching the right edge: remember "right edge" so the bubble and the wider panel both hug it.
        anchorX = if (params.x > 0 && params.x >= dm.widthPixels - root.width) Int.MAX_VALUE else params.x
        anchorY = params.y
        prefs().edit().putInt(KEY_X, anchorX).putInt(KEY_Y, anchorY).apply()
    }

    /** Dragging this view moves the floating window; a tap without movement counts as a click. */
    private fun View.dragsWindow() {
        val slop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        anchorX = startX + dx.roundToInt()
                        anchorY = startY + dy.roundToInt()
                        place()
                    }
                }
                MotionEvent.ACTION_UP -> if (dragging) savePosition() else v.performClick()
            }
            true
        }
    }

    // ---- views ----

    /** Collapsed: a small pill with 3 colors (green border while reading the game). */
    private fun buildBubble(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = rounded(PANEL_DARK, 20, if (reading) LIVE else GOLD, 2)
        setPadding(dp(6), dp(5), dp(6), dp(5))
        val colors: List<GameColor>? = if (reading) hotColors()?.first?.map { it.color } else picks
        repeat(3) { i ->
            val c = colors?.getOrNull(i)
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c?.argb?.toInt() ?: PLACEHOLDER)
                    setStroke(dp(1), 0x66000000)
                }
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { setMargins(0, dp(3), 0, dp(3)) })
        }
        contentDescription = "CG Picker: ${colors?.joinToString { it.label } ?: "waiting for results"}. Tap to open."
        setOnClickListener { expanded = true; render() }
        dragsWindow()
    }

    private fun buildPanel(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(PANEL, 16, if (reading) LIVE else GOLD, 1)
        setPadding(dp(8), dp(2), dp(8), dp(10))
        layoutParams = FrameLayout.LayoutParams(dp(244), WRAP)
        if (reading) fillReadingPanel(this) else fillRandomPanel(this)
    }

    private fun header(title: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            textView(title, 15f, Color.WHITE, bold = true).apply { setPadding(dp(4), 0, 0, 0) },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        addView(iconButton("–", "Minimize") { expanded = false; render() })
        addView(iconButton("×", "Close floating picker") { stopSelf() })
        dragsWindow()
    }

    private fun chipRow(chips: List<View>): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        chips.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(3), 0, dp(3), 0) }) }
    }

    private fun buttonRow(vararg buttons: Pair<View, Float>): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEachIndexed { i, (b, weight) ->
            val lp = if (weight > 0f) LinearLayout.LayoutParams(0, dp(36), weight) else LinearLayout.LayoutParams(WRAP, dp(36))
            if (i > 0) lp.marginStart = dp(6)
            addView(b, lp)
        }
    }

    private fun LinearLayout.addFull(v: View, topDp: Int) =
        addView(v, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(3), dp(topDp), dp(3), 0) })

    /** Random mode: 3 random colors, each with its real chance. */
    private fun fillRandomPanel(panel: LinearLayout) = with(panel) {
        addView(header("Your 3 picks"), LinearLayout.LayoutParams(MATCH, WRAP))
        addView(chipRow(picks.map { chip(it, "${pct(ColorStats.FAIR_HIT_RATE)} chance") }), LinearLayout.LayoutParams(MATCH, WRAP))
        addFull(
            buttonRow(
                pillButton("New picks", GOLD, ON_GOLD, outline = false) { picks = ColorStats.quickPicks(); render() } to 1f,
                pillButton("Tracker", PANEL_DARK, Color.WHITE, outline = true) { openTracker() } to 0f,
            ),
            10,
        )
        addFull(
            buttonRow(pillButton("Read results from game", PANEL_DARK, Color.WHITE, outline = true) { requestReading() } to 1f),
            6,
        )
        addFull(
            textView(
                "Every color has the same ${pct(ColorStats.FAIR_HIT_RATE)} chance each round, so these " +
                    "picks are random. Betting on all 3: ${pct(ColorStats.THREE_PICKS_ANY_HIT)} at least one hits, " +
                    "but ${pct(ColorStats.THREE_PICKS_BEHIND)} of rounds you lose money.",
                10.5f, MUTED,
            ),
            8,
        )
    }

    /** Reading mode: the most frequent colors in the real results read from the game. */
    private fun fillReadingPanel(panel: LinearLayout) = with(panel) {
        val hot = hotColors()
        addView(header("Most frequent lately"), LinearLayout.LayoutParams(MATCH, WRAP))
        addFull(
            textView(
                if (stripVisible) "● Reading results live · ${history.size} saved"
                else "○ Waiting for the results strip (betting screen)",
                11f, if (stripVisible) LIVE else MUTED, bold = stripVisible,
            ),
            0,
        )
        if (hot == null) {
            addFull(
                textView(
                    "No results yet. Keep the game's betting screen open and don't let this " +
                        "panel cover the small result squares at the bottom.",
                    12f, Color.WHITE,
                ),
                8,
            )
        } else {
            val (top, n) = hot
            addFull(chipRow(top.map { chip(it.color, "${it.roundsHit}/$n rounds") }), 6)
        }
        addFull(
            buttonRow(
                pillButton("Stop reading", GOLD, ON_GOLD, outline = false) { stopReading() } to 1f,
                pillButton("Tracker", PANEL_DARK, Color.WHITE, outline = true) { openTracker() } to 0f,
            ),
            10,
        )
        addFull(
            textView(
                (if (hot != null) "The colors that came up most in the last ${hot.second} real results. " else "") +
                    "This is history, not a forecast: the dice don't remember, so every color still has a " +
                    "${pct(ColorStats.FAIR_HIT_RATE)} chance next round.",
                10.5f, MUTED,
            ),
            8,
        )
    }

    private fun chip(c: GameColor, caption: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = rounded(c.argb.toInt(), 10, GOLD, 2)
        setPadding(dp(2), dp(8), dp(2), dp(8))
        val fg = if (c.darkText) DARK_TEXT else Color.WHITE
        addView(textView(c.label, 14f, fg, bold = true))
        addView(textView(caption, 10f, fg))
    }

    private fun iconButton(symbol: String, description: String, onClick: () -> Unit): TextView =
        textView(symbol, 20f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(38)
            minHeight = dp(38)
            contentDescription = description
            setOnClickListener { onClick() }
        }

    private fun pillButton(label: String, bg: Int, fg: Int, outline: Boolean, onClick: () -> Unit): TextView =
        textView(label, 13f, fg, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(bg, 18, if (outline) MUTED else null, 1)
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { onClick() }
        }

    private fun textView(s: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int, radiusDp: Int, stroke: Int?, strokeDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(strokeDp), stroke)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun pct(x: Double) = "${(x * 100).roundToInt()}%"

    // ---- foreground notification & navigation ----

    private fun trackerIntent() = Intent(this, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_OPEN_TRACKER, true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun openTracker() {
        expanded = false
        render()
        startActivity(trackerIntent())
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Floating picker", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, trackerIntent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_picker)
            .setContentTitle(if (projecting) "CG Picker is reading the game" else "CG Picker is floating")
            .setContentText("Tap to open the tracker")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    (if (projecting) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0),
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && projecting -> startForeground(
                NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
            else -> startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val ACTION_STOP = "com.jenalie.cgpicker.STOP_OVERLAY"
        private const val ACTION_START_READING = "com.jenalie.cgpicker.START_READING"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "floating_picker"
        private const val NOTIFICATION_ID = 1
        private const val MIN_ROUNDS = 3

        // Shared with MainActivity (same preferences file and keys).
        private const val PREFS = "cg_picker"
        private const val KEY_HISTORY = "history"
        private const val KEY_WINDOW = "window"
        private const val KEY_X = "overlay_x"
        private const val KEY_Y = "overlay_y"
        private const val KEY_STRIP_DIR = "strip_direction"
        private const val KEY_STRIP_LAST = "strip_last"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        }

        /** Called with the user's screen-capture consent. */
        fun startReading(context: Context, resultCode: Int, data: Intent) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayService::class.java)
                    .setAction(ACTION_START_READING)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, data),
            )
        }
    }
}
