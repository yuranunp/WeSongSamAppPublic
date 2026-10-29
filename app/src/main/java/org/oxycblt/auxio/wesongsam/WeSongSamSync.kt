/*
 * Copyright (c) 2026 WeSongSam Project
 * WeSongSamSync.kt is part of WeSongSam.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.oxycblt.auxio.wesongsam

import javax.inject.Inject
import javax.inject.Singleton
import org.oxycblt.auxio.music.MusicRepository
import timber.log.Timber as L

/** Counts of the outcomes of applying a move log. */
data class ApplyCounts(
    var moved: Int = 0,
    var already: Int = 0,
    var notFound: Int = 0,
    var ambiguous: Int = 0,
    var noFolder: Int = 0,
)

/** One parsed row of a move log. */
data class LogRow(
    val time: String,
    val action: String,
    val sourcePath: String,
    val slot: Int,
    val label: String,
)

/** A parsed `button.<key>.<presses>` profile line. */
data class ButtonSpec(val keyIndex: Int, val presses: Int, val action: WssButtonAction)

/**
 * Parse one move log row. Null when the line does not have exactly five TAB-separated fields or
 * the slot is not a number; the action is not filtered here (see [isApplicableLogRow]).
 */
fun parseLogRow(line: String): LogRow? {
    val fields = line.split('\t')
    if (fields.size != 5) {
        return null
    }
    val slot = fields[3].toIntOrNull() ?: return null
    return LogRow(fields[0], fields[1], fields[2], slot, fields[4])
}

/** True when [row] is a move that [WeSongSamSync.applyLog] applies on a device with [folderCount] slots. */
fun isApplicableLogRow(row: LogRow, folderCount: Int): Boolean {
    if (row.action != WeSongSamActionLog.ACTION_MOVE) {
        return false
    }
    return row.slot in 1..folderCount
}

/** The file name after the last `/` or `\` of a Windows or Android path. */
fun fileNameFromPath(path: String): String =
    path.substringAfterLast('/').substringAfterLast('\\')

/**
 * Parse profile text into `key`/`value` pairs in file order (mood and situation keys repeat and
 * their order matters, so a map would lose information). Comment and keyless lines are dropped.
 */
fun parseProfile(text: String): List<Pair<String, String>> {
    val entries = mutableListOf<Pair<String, String>>()
    for (rawLine in text.split("\r\n", "\n")) {
        val line = rawLine.trimEnd('\r')
        if (line.isEmpty() || line.startsWith("#")) {
            continue
        }
        val fields = line.split('\t', limit = 2)
        if (fields.size == 2) {
            entries.add(fields[0] to fields[1])
        }
    }
    return entries
}

/**
 * Parse a `button.<key>.<presses>` profile line. Null when the key names no known media button,
 * the press count is out of range, or the value is not a button action name.
 */
fun parseButtonKey(key: String, value: String): ButtonSpec? {
    val parts = key.split('.')
    if (parts.size != 3 || parts[0] != "button") {
        return null
    }
    val keyIndex =
        when (parts[1]) {
            "prev" -> WeSongSamSettings.KEY_PREV
            "next" -> WeSongSamSettings.KEY_NEXT
            "play" -> WeSongSamSettings.KEY_PLAY
            else -> return null
        }
    val presses = parts[2].toIntOrNull() ?: return null
    if (presses !in 1..WeSongSamSettings.MAX_PRESSES) {
        return null
    }
    val action = WssButtonAction.entries.find { it.name == value } ?: return null
    return ButtonSpec(keyIndex, presses, action)
}

/**
 * Build the shared `WeSongSam_profile.txt` text from plain values, so the format can be tested
 * without Android. [labels] holds the four move button labels, [buttonActions] maps a media
 * button index (KEY_PREV/KEY_NEXT/KEY_PLAY) to its action per press count.
 */
fun exportProfileText(
    labels: List<String>,
    moods: List<String>,
    situations: List<String>,
    buttonActions: Map<Int, Map<Int, WssButtonAction>>,
    multiPressMs: Int,
): String {
    val lines = mutableListOf("# WeSongSam profile", "version\t1")
    labels.forEachIndexed { index, label -> lines.add("slot.${index + 1}.label\t$label") }
    moods.forEach { mood -> lines.add("mood\t$mood") }
    situations.forEach { situation -> lines.add("situation\t$situation") }
    val keyNames = listOf("prev", "next", "play")
    for (key in 0 until WeSongSamSettings.KEY_COUNT) {
        for (presses in 1..WeSongSamSettings.MAX_PRESSES) {
            val action = buttonActions[key]?.get(presses) ?: WssButtonAction.NORMAL
            lines.add("button.${keyNames[key]}.$presses\t${action.name}")
        }
    }
    lines.add("multiPressMs\t$multiPressMs")
    return lines.joinToString("\r\n") + "\r\n"
}

/**
 * Shares configuration and moves with the Windows plug-in through the two plain-text formats of
 * `docs/SHARED_LOG_PROFILE.md`: applies a move log written on the other device, and exports or
 * imports the profile (`WeSongSam_profile.txt`).
 */
@Singleton
class WeSongSamSync
@Inject
constructor(
    private val wesongsamSettings: WeSongSamSettingsImpl,
    private val mover: WeSongSamMover,
    private val musicRepository: MusicRepository,
) {
    /**
     * Apply a move log: for every MOVE_TO_FOLDER row, move each matching song of this device's
     * library into the folder of the row's slot.
     */
    suspend fun applyLog(text: String): ApplyCounts {
        val counts = ApplyCounts()
        val songs = musicRepository.library?.songs ?: return counts
        val folderCount = wesongsamSettings.folders.size
        for (line in text.split("\r\n", "\n")) {
            val row = parseLogRow(line) ?: continue
            if (!isApplicableLogRow(row, folderCount)) {
                continue
            }
            val name = fileNameFromPath(row.sourcePath)
            val matches = songs.filter { it.path.name?.equals(name, ignoreCase = true) == true }
            if (matches.isEmpty()) {
                counts.notFound++
                continue
            }
            if (matches.size > 1) {
                counts.ambiguous++
                continue
            }
            if (wesongsamSettings.folders[row.slot - 1].treeUri == null) {
                counts.noFolder++
                continue
            }
            when (mover.moveToFolder(matches.single(), row.slot)) {
                MoveResult.Done -> counts.moved++
                MoveResult.AlreadyThere -> counts.already++
                MoveResult.NoFolder -> counts.noFolder++
                is MoveResult.Error -> counts.notFound++
            }
        }
        return counts
    }

    /** Export the profile as the shared `WeSongSam_profile.txt` text. */
    fun exportProfile(): String {
        val folders = wesongsamSettings.folders
        val buttonActions =
            (WeSongSamSettings.KEY_PREV..WeSongSamSettings.KEY_PLAY).associateWith { key ->
                (1..WeSongSamSettings.MAX_PRESSES).associateWith {
                    wesongsamSettings.buttonAction(key, it)
                }
            }
        return exportProfileText(
            labels = folders.map { it.label },
            moods = wesongsamSettings.moods.map { it.name },
            situations = wesongsamSettings.situations.map { it.name },
            buttonActions = buttonActions,
            multiPressMs = wesongsamSettings.multiPressMs,
        )
    }

    /**
     * Import a profile: change only the keys present in [text].
     *
     * @return True when at least one known key was found and applied.
     */
    fun importProfile(text: String): Boolean {
        var applied = false
        val moods = mutableListOf<String>()
        val situations = mutableListOf<String>()
        for ((key, value) in parseProfile(text)) {
            val labelMatch = Regex("slot\\.([1-4])\\.label").matchEntire(key)
            when {
                key == "version" -> applied = true
                labelMatch != null -> {
                    val index = labelMatch.groupValues[1].toInt() - 1
                    val folder = wesongsamSettings.folders[index]
                    wesongsamSettings.setFolder(index, folder.treeUri, value)
                    applied = true
                }
                key == "mood" && value.isNotEmpty() -> moods.add(value)
                key == "situation" && value.isNotEmpty() -> situations.add(value)
                parseButtonKey(key, value) != null -> {
                    val spec = parseButtonKey(key, value)!!
                    wesongsamSettings.setButtonAction(spec.keyIndex, spec.presses, spec.action)
                    applied = true
                }
                key == "multiPressMs" && value.toIntOrNull() != null -> {
                    wesongsamSettings.setMultiPressMs(value.toInt())
                    applied = true
                }
            }
        }
        if (moods.isNotEmpty()) {
            wesongsamSettings.setMoods(moods)
            applied = true
        }
        if (situations.isNotEmpty()) {
            wesongsamSettings.setSituations(situations)
            applied = true
        }
        return applied
    }
}
