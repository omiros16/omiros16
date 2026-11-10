# CLAUDE.md

Two personal Android apps for one user (Greek speaker, Motorola G86 5G, Android 15). Talk to the
user in Greek, plainly; they are not a developer. All in-app text is Greek (exercise names are often
English, and the two backup buttons are deliberately English: "Export your workout data" /
"Insert your workout data").

| App | Source | Build | APK shipped in repo |
|---|---|---|---|
| **Gym Notes** (active) | `gym/app/src/main` | `gym/build.sh` → `gym/build/GymNotes.apk` | `apk/GymNotes.apk` |
| Timetable (older, done) | `app/src/main` | `./build.sh` → `build/Timetable.apk` | `apk/Timetable.apk` |

User-facing docs: `gym/README.md` (Greek, with screenshots in `gym/docs/`), root `README.md`.

## Build (no Android SDK, no Gradle)

`gym/build.sh` downloads everything from Maven Central into `.tools/` (gitignored): aapt2 (from the
apktool jar), `android-all` (Robolectric's android.jar, used only to compile), dalvik-dx and apksig.
Needs JDK 17+, python3, curl. Steps: aapt2 compile/link → `javac --release 8` → dx → `tools/package.py`
→ `tools/Sign.java` (v2 signature).

Hard constraints that follow from this toolchain:

- **Java 8 language level, no lambdas, no method references**: old dx cannot
  dex `invokedynamic`. Use anonymous classes (the whole codebase does). Check with
  `javap -c -p -cp gym/build/classes com.omiros.gymnotes.X | grep invokedynamic` (must be empty).
- **No AndroidX / libraries.** Framework APIs only, minSdk 26, targetSdk 34.
- **No XML layouts.** Every view is built in Java through `Ui` helpers.
- Signing key: `keystore/gymnotes.p12`, password `gymnotes`, alias `gymnotes`. Never change it: the
  same key lets updates install over the old app without losing data.

### Shipping a new version

1. Bump `VERSION_CODE` and `VERSION_NAME` in `gym/build.sh` (currently 6 / 1.4).
2. `bash gym/build.sh`, then `cp gym/build/GymNotes.apk apk/GymNotes.apk`.
3. Update `gym/README.md` (and screenshots if the UI changed).
4. The user installs by downloading the APK onto the phone (send them `apk/GymNotes.apk`).

## Gym Notes architecture (`gym/app/src/main/java/com/omiros/gymnotes/`)

Single `MainActivity` with a hand-rolled back stack of `Screen`s. Each screen has a `key()`
(`home:TAB`, `preset:ID`, `log:EXERCISE_ID:DAY`, `history:ID`) so the stack is restored after process
death (`Screen.restore`). Days are `LocalDate.toEpochDay()` longs everywhere.

| File | Role |
|---|---|
| `MainActivity` | Stack, back handling, SAF export/import, background executor |
| `Screen` | Base class: shared header (title + subtitle), rename/delete exercise, rep-range/step dialog, "how the target works" text |
| `HomeScreen` | Tabs "Προγράμματα" (presets, max 7, + export/import) and "Ασκήσεις" (exercise library, search) |
| `PresetScreen` | One program: its exercises with target / last time / PR, exercise picker dialog |
| `ExerciseScreen` | Logging one exercise on one day: target, previous sets table, set rows with −/+ steppers, tick, per-set "Πώς ήταν;" rating, PR banner, next-time target |
| `HistoryScreen` | Stats, `ChartView` (≈1RM per session), all sessions |
| `Db` | SQLite (`gym.db`, version 5), all queries, cached per-exercise stats |
| `Coach` | Progression advice: what to lift next time (pure logic, no Android) |
| `Strength` | e1RM (Wathan curve, reps capped at 12) and PR detection (pure logic) |
| `Session` | One exercise on one day: `weight[]`, `reps[]`, `feel[]`, `flags` |
| `Backup` | JSON export/import (streamed), merge or replace |
| `Fmt` | All text formatting (kg, dates, set lists, Greek labels) |
| `Theme` / `Ui` | Colour tokens, fonts, view factory, dialogs |

### Data model

- `exercise`: one row per exercise, **shared by every preset that contains it** (logging Lat pulldown
  in Friday's program shows in Monday's too). `key` = normalized name (unique). Cached stats kept
  up to date on every write: `last_day, sessions, best_e1rm, max_weight, last_pr, auto_low,
  auto_high, auto_step`. User overrides: `rep_low, rep_high, step` (0 = automatic).
- `preset`, `preset_item` (ordered exercises per preset).
- `log_set`: `WITHOUT ROWID`, PK `(exercise_id, day, idx)`, so one exercise's history is contiguous on
  disk. `feel`: 0 unrated, 1 EASY ("Χαλαρό", 3+ reps left), 2 NORMAL ("Μέτριο", 1–2), 3 HARD
  ("Στο όριο", 0).
- Performance goal: stays fast after years of data. Lists read only cached columns, never the whole
  history; the coach reads only the last `Coach.LOAD` (17) sessions.
- Schema changes: bump the version in `Db`'s constructor and add an `onUpgrade` step that keeps
  existing data (users upgrade in place). Also keep `Backup` able to read older export files.

### PR rules (`Strength`): never weight × reps

A set is a PR only against all earlier sessions of that exercise: heavier weight ever (WEIGHT),
higher estimated 1RM (E1RM, Wathan: weight / (0.488 + 0.538 e^(-0.075 reps)), reps capped at 12 so 1 kg × 100 is never a PR), or more reps
at a weight already used and ≥ 75% of the heaviest (REPS). The first session is the baseline, never a PR.

Records use only weight and reps, never the rating (the user asked for this explicitly). The rating
enters only `Strength.e1rm(w, r, feel)` (2.5% of the max per rep left; shown in History as
"Δύναμη τώρα ≈1RM") and the coach. The coach still predicts reps with Epley internally: switching
it to Wathan was tried and was no better in the simulation.

### Coach (`Coach.next`): the most delicate code; read its comments before touching it

Double progression, built set by set from the per-set ratings: EASY +2 reps, NORMAL/unrated +1,
HARD same. When every set reaches the top of the rep range, it goes up one weight step. Rep range is
auto-detected from the first 3 sessions; the step from all work-set weights used (not warm-ups). It judges each session
against the person's usual form: a bad day never cuts weight (the same target is retried), a great
day moves at most one step. The bad/good thresholds start at −5% / +6% and adapt to the person's own
day-to-day spread (`spread()`: own share = samples / (samples + 6), from up to 16 sessions; shown as
"Διακύμανση μέρας" in History). Also: stall (4 sessions, no progress, last one not a dip) → ~10% lighter; reps under the range →
a lighter weight, unless the advice itself just moved the weight up or it was near the range recently; breaks of 14+
days → repeat, 28+ → lighter; never more than +10% in one jump. The user explicitly asked that
advice be reliable or not exist at all, so any change here must be checked against many simulated
lifters, not one example.

## UI design (version 1.3, the user asked for "clean, not AI-looking, not extreme")

- Tokens in `Theme`: background `BG #141414`, panels `CARD #1E1E1E`, inputs `FIELD #2A2A2A`,
  hairlines `LINE`, text `TEXT` (chalk white) / `SOFT` / `DIM`. **One** accent, `MARK` (yellow),
  used only for the target ("Στόχος" / "Επόμενη φορά") and PRs. Feel colours (green / blue / red)
  are used only for set ratings.
- Fonts bundled in `res/font` (Latin + Greek subsets, OFL, see `gym/FONT-LICENSE.txt`):
  Sofia Sans (`ui.regular/medium/semibold`) for text, Sofia Sans Condensed (`ui.figures/figuresBold`,
  tabular figures) for weights, reps and titles. `Ui.quiet()` shrinks and greys "kg" and "×".
- Grouped panels with hairlines (`ui.group()` + `ui.addRow()`), not separate cards. No emoji, no
  outlined boxes, no "A · B · C" meta strings (the "·" inside `Fmt.sets` set notation is intended).
  Icons are vector drawables in `res/drawable`.

## Testing

`gym/test.sh` builds the app and runs the suite in `gym/test` (Maven + Robolectric 4.14, about a
minute; extra arguments go to Maven, e.g. `-Dtest=CoachTest`). Run it after every change; add or
adjust tests with the change.

- `StrengthTest`: PR rules, formatting, parsing.
- `CoachTest`: one situation per test for every coach rule. Expected numbers are worked out by hand
  from the rule, not copied from the output.
- `CoachSimTest`: 300 simulated people x 6 ways of training x 109 sessions with known real strength.
  Checks invariants (no unsafe jump, never up then straight back down, a bad day almost never
  lowers the weight, targets track real strength, ratings help). It writes `target/coach-sim.txt`;
  read it before and after any change to `Coach` and keep the README's "Επαλήθευση" numbers in sync.
- `GymUiTest`: the real Activity: a whole workout, export / import, upgrades from DB versions 1-3,
  two years of data. `gym/test.sh -Dshots=<folder>` also writes the screenshots used in `gym/docs`.

How it works: the pom adds `gym/app/src/main/java` and `gym/build/gen` (R.java) as sources and uses
`.tools/android-all.jar`; `test_config.properties` points Robolectric at `gym/build/base.apk`.
Google's Maven is not reachable from here, so `src/test/java/androidx/test` holds stand-ins for the
few `androidx.test:monitor` classes Robolectric calls (see the README there). Maven Central sometimes
answers 429; just run again. Tests may use lambdas (they are not dexed); app code may not.
Nothing has been run on a real device by Claude; the user tests on their phone.

## Git

Work on the branch the session names; commit with clear messages; don't open PRs unless asked.
