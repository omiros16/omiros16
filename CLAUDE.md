# CLAUDE.md

Timetable: a personal Android day planner in 15-minute slots (05:00-24:00) for one user (Greek
speaker, Motorola G86 5G, Android 15). Talk to the user in Greek, plainly; they are not a developer.
All in-app text is Greek. User-facing docs: `README.md` (Greek, screenshots in `docs/`).

Gym Notes, which used to live here as `gym/`, moved with its history to its own private repo,
omiros16/gym-notes; work on it there, not here.

## Build (no Android SDK, no Gradle)

`./build.sh` → `build/Timetable.apk`; the APK the user installs is committed as `apk/Timetable.apk`.
The script downloads its tools from Maven Central into `.tools/` (gitignored) and checks each against
the SHA-256 written in it: aapt2 (from the apktool jar), `android-all` (Robolectric's android.jar,
compile only), dalvik-dx and apksig. Needs JDK 17+, python3, curl, sha256sum. Steps: aapt2
compile/link → `javac --release 8` → dx → `tools/package.py` → `tools/Sign.java` (v2 signature).

- **Java 8 language level, no lambdas, no method references**: old dx cannot dex `invokedynamic`.
  Use anonymous classes, as the code does.
- **No AndroidX / libraries.** Framework APIs only, minSdk 26, targetSdk 34. No XML layouts.
- Signing key: `keystore/timetable.p12`, password `timetable`, alias `timetable`. Never change it:
  the same key lets updates install over the old app without losing data.
- New version: bump `VERSION_CODE` / `VERSION_NAME` in `build.sh` (currently 7 / 1.6), build, copy
  the APK to `apk/`, update `README.md`.

## Code (`app/src/main/java/com/omiros/timetable/`)

`MainActivity` (the whole UI), `DayStore` (one small JSON file per day in `files/days/`),
`Reminders` + `ReminderReceiver` + `RescheduleReceiver` (exact alarms at the start of each
activity, re-armed after reboot), `Theme` (colours). There are no automated tests; nothing has been
run on a real device by Claude.

## Git

Work on the branch the session names; commit with clear messages; don't open PRs unless asked.
