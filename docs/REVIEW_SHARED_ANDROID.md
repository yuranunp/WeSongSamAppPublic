# Review of the shared move log and profile (Android side)

Review of the uncommitted Android changes for the shared move log and profile
(`docs/SHARED_LOG_PROFILE.md`), with fixes and JVM unit tests. Nothing was committed.

## What was checked

- `WeSongSamSync.kt` (new): `applyLog`, `exportProfile`, `importProfile` against the spec —
  log file row format (5 TAB-separated fields), slot range 1-4, case-insensitive file-name
  matching on the text after the last `/` or `\`, the five outcome counts, profile keys and
  "change only keys present".
- `WeSongSamMover.kt`: the new `MoveResult.AlreadyThere` (same tree document check).
- `WeSongSamActionLog.kt`: the file rename to `WeSongSam_actions_android.log` and the row format
  (tab/newline characters are stripped from fields).
- `WeSongSamActionHandler.kt`: every `when` over `MoveResult` now handles `AlreadyThere`
  (the only other `when` lives in `WeSongSamSync.applyLog`).
- `WeSongSamSettingsFragment.kt`: the import button (JSON vs profile detection, UI refresh),
  the new apply-log and export-profile flows (detached-Fragment safety, unreadable Uri handling).
- Layout and strings (English + Thai) for the new sync section.

## Bugs found and fixed

1. **`WeSongSamSettingsFragment.applyLog` crashed if the user leaves the screen while the log is
   being applied** — it called `requireContext()` inside the coroutine that runs on `Dispatchers.IO`
   and again after `withContext(Dispatchers.Main)`, which throws `IllegalStateException` once the
   Fragment is detached. Fixed by capturing the application context before launching and using it
   for all work and toasts (`WeSongSamSettingsFragment.applyLog`). The coroutine is still bound to
   `viewLifecycleOwner`, so it is cancelled when the view goes away.

2. **JSON vs profile detection failed on a BOM or leading whitespace** — the import button decided
   between `WeSongSam_phone.json` and `WeSongSam_profile.txt` with `text.startsWith("{")`, so a
   file with a UTF-8 BOM (common on Windows-exported files) was fed to the profile importer and
   rejected. Fixed by trimming BOM/whitespace before the check (`WeSongSamSettingsFragment.importJson`).

3. **`exportProfile` did not export the button actions it actually read back** — refactored into
   the pure `exportProfileText(labels, moods, situations, buttonActions, multiPressMs)` so the
   format is unit-testable; the class method now just feeds it the current settings. No format
   change (verified by round-trip test).

4. **Untestable parsing** — `applyLog`'s row parsing and `importProfile`'s line parsing were
   inlined in Android-dependent classes. Extracted the pure functions `parseLogRow`,
   `isApplicableLogRow`, `fileNameFromPath`, `parseProfile`, and `parseButtonKey` in
   `WeSongSamSync.kt` and covered them with JVM tests. No behavior change, except that profile
   parsing now also accepts a lone-LF file without a trailing newline (it already did per line).

## Verified correct (no change)

- Log file name `WeSongSam_actions_android.log` matches the spec; the Windows counterpart file is
  only read, never written.
- Rows without exactly 5 fields, non-numeric/out-of-range slots, and non-`MOVE_TO_FOLDER` actions
  are skipped; empty and huge files cannot crash the parser (pure string splitting).
- Case-insensitive name match against `song.path.name`; not-found / ambiguous / no-folder /
  already-there / moved counting matches the spec; re-applying a log is idempotent via
  `MoveResult.AlreadyThere`.
- Import changes only the keys present: labels only rewrite the label (tree Uri kept), moods and
  situations are only replaced when the file contains at least one such line, unknown keys and
  comment lines are ignored, `multiPressMs` outside 250-1000 is clamped by `setMultiPressMs`.
- Unreadable Uri (null stream or exception) shows the failure toast on both import and apply-log.
- UI refresh after a successful import covers folders, button rows, mood/situation inputs, the
  multi-press slider, and the log summary.

## Tests

`app/src/test/java/org/oxycblt/auxio/wesongsam/WeSongSamSyncTest.kt` — 21 JVM JUnit tests covering:
valid row; mood/moment row with an empty slot; wrong action; slot 0/5/non-numeric; lines without
TABs; too few/too many fields; Windows, Android, mixed, and bare paths; CRLF vs LF parsing; profile
round trip (export then parse gives the same labels, moods, situations, button actions, and
multiPressMs); comments/unknown keys; empty values; and `parseButtonKey` rejection cases.

Results: `:app:testDebugUnitTest` 21 tests, 0 failures; `:app:assembleDebug` succeeded;
`:app:compileDebugKotlin` (`android_check.bat` command) passes. JUnit was added to
`app/build.gradle` (`testImplementation "junit:junit:4.13.2"`) because the app module had no test
dependency yet.

## Notes

- The provided `android_check.bat` printed nothing in this shell; the exact same gradle command
  with the same `JAVA_HOME`/`PATH` was run directly and passes.
- Two initial test failures were wrong test expectations (mood rows legitimately do not parse
  without a slot; `parseProfile` keeps unknown keys and it is `importProfile` that ignores them),
  fixed in the tests, not in the code.
