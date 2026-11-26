# CG Picker

Android app (Kotlin + Jetpack Compose) for tracking results of the 3-dice **Color Game**
(Yellow, White, Pink, Blue, Red, Green) and showing the **Top 3** colors by recent frequency.

## Features
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
