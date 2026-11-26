# CG Picker

Android app (Kotlin + Jetpack Compose) for tracking results of the 3-dice **Color Game**
(Yellow, White, Pink, Blue, Red, Green) and showing the **Top 3** colors by recent frequency.

## Floating picker (over the Color Game)
Open the game (e.g. Casino Plus), then open **CG Picker**: a small bubble floats on top of the game.
Once started it stays on top of every app until you close it.
- **Bubble** (collapsed): shows your 3 colors as dots. Drag it anywhere; tap it to open the panel.
- **Panel**: the 3 colors in board order, each with its real chance (42%), plus **New picks**,
  **Tracker** (opens the full app), **–** (back to the bubble) and **×** (close).
- No recording needed. With fair dice every color has the same chance, so the 3 picks are random.
  Any 3 colors are as good as any other 3.
- **Read results from game**: CG Picker reads each result from the small result squares at the bottom
  of the Casino Plus betting screen (Android asks once per session to allow screen capture; choose the
  entire screen). Results are saved to the tracker's history automatically, and the panel shows the
  3 colors that came up most in your look-back window (default: last 20 rounds) with how often each hit.
  The bubble gets a green border while reading. Keep the bubble off the result squares, or it blocks the reader.
  This shows history, not a forecast: the next round is still 42% for every color.
  The square positions were measured on a 1256×2760 screen; another phone shape may need re-measuring.
- First run asks for **Display over other apps**. Turn it on for CG Picker and come back.
- If the bubble doesn't show over the game: on Xiaomi/Redmi/POCO also enable
  *Other permissions → Display pop-up windows while running in the background*. Some apps hide
  overlays on purpose; if the game does, the bubble disappears only while that app is open.

## Tracker features
- **Colors to bet on (toggle)**: shows 3 picks (the colors that appeared most in your look-back window,
  recent rounds weigh a bit more) with each color's real win chance (42%) and its recent hit rate.
  It also shows the odds of betting on all 3: 87.5% at least one wins, 37.5% you end up ahead,
  12.5% break even, 50% you lose money. Ranks also show as gold badges on the board.
- **Board** in the same 3×2 layout as the game: share of dice per color and how many rounds since it last hit.
- **Look-back window**: last 10 / 20 / 50 / All rounds.
- **Quick entry**: tap 3 colors → *Save round*. Undo and Reset are available.
- **History strip**, newest first. Data is saved on the device.

## Important
With fair dice every roll is independent: each color shows on at least one die about **42%** of the time,
regardless of past rounds. The picks summarize history; they are **not a prediction**.

## Build
- **Android Studio**: open the folder and press Run.
- **CLI**: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- **GitHub Actions**: every push to `main` builds the APK. Download it from the run's *Artifacts* section.
