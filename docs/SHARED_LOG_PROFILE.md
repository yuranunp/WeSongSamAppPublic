# Shared move log and profile (Windows plug-in + Android app)

Both programs write and read the same two plain-text formats: UTF-8, TAB-separated, lines end with CRLF (LF is accepted when reading). Put the files in one folder that both devices see, for example a cloud drive folder or a folder copied by USB.

## 1. Move log

Each device appends to its own file, so a sync never mixes two writers:

- Windows plug-in: `WeSongSam_actions_windows.log`
- Android app: `WeSongSam_actions_android.log`

One row per action:

```
time<TAB>action<TAB>source path<TAB>slot<TAB>label
```

| Field | Meaning |
|---|---|
| time | Local time, `yyyy-MM-dd HH:mm:ss` |
| action | `MOVE_TO_FOLDER` for a move. Other actions (`ADD_BY_MOOD`, `MUSIC_FOR_MOMENT`) are written by Android and ignored when a log is applied |
| source path | Full path of the file on the device that moved it (`\` or `/`) |
| slot | Move button number, 1-4 |
| label | The button label at that time (for people reading the log) |

### Applying a log ("Apply move log…" on both sides)

For every `MOVE_TO_FOLDER` row, in file order:

1. Name = the text after the last `/` or `\` of the source path.
2. Find the songs in this device's library whose file name equals the name (case-insensitive).
3. No match: **not found**. More than one match: **ambiguous**, skipped. The local slot has no folder: **no folder**.
4. One match already inside the local folder of that slot: **already there**.
5. Otherwise move it into the local folder of that slot, with the normal move code of the device (Windows: same as a move button, so Undo works).

Applying the same log again is safe: moved songs are then "already there". At the end the device shows the counts: moved, already there, not found, ambiguous, no folder.

## 2. Profile

File: `WeSongSam_profile.txt`. Lines `key<TAB>value`. Lines starting with `#` and unknown keys are ignored. Import changes only the keys that are in the file.

| Key | Value | Used by |
|---|---|---|
| `version` | `1` | both |
| `slot.1.label` … `slot.4.label` | move button label | both |
| `mood` (one line per mood, in order) | mood name; the first three are the Android playlist buttons | Android |
| `situation` (one line each, in order) | situation name | Android |
| `button.prev.1` … `button.play.3` (keys `prev`, `next`, `play`; presses 1-3) | `NORMAL`, `NOTHING`, `MOVE_1`…`MOVE_4`, `MOOD_1`…`MOOD_3` | Android |
| `multiPressMs` | 250-1000 | Android |

Folders are not part of the profile: each device chooses its own folder for each slot.
