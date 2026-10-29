/*
 * Copyright (c) 2026 WeSongSam Project
 * WeSongSamSyncTest.kt is part of WeSongSam.
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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the pure text handling of the shared move log and profile. */
class WeSongSamSyncTest {
    // --- parseLogRow ---

    @Test
    fun validRowParses() {
        val row = parseLogRow("2026-10-29 12:00:00\tMOVE_TO_FOLDER\tC:\\Music\\Song.mp3\t2\tCalm")
        assertEquals("2026-10-29 12:00:00", row!!.time)
        assertEquals("MOVE_TO_FOLDER", row.action)
        assertEquals("C:\\Music\\Song.mp3", row.sourcePath)
        assertEquals(2, row.slot)
        assertEquals("Calm", row.label)
    }

    @Test
    fun wrongActionIsStillParsedButNotApplicable() {
        val row = parseLogRow("2026-10-29 12:00:00\tMUSIC_FOR_MOMENT\t/a/b/s.mp3\t3\tMorning")
        assertEquals("MUSIC_FOR_MOMENT", row!!.action)
        assertFalse(isApplicableLogRow(row, 4))
    }

    @Test
    fun moodRowWithEmptySlotDoesNotParse() {
        // Mood/moment rows are written without a slot; applyLog ignores them either way.
        assertNull(parseLogRow("2026-10-29 12:00:00\tADD_BY_MOOD\t/a/b/s.mp3\t\tHappy"))
    }

    @Test
    fun badSlotIsNotApplicable() {
        val row = parseLogRow("t\tMOVE_TO_FOLDER\tp\t5\tlabel")
        assertEquals(5, row!!.slot)
        assertFalse(isApplicableLogRow(row, 4))
        assertTrue(isApplicableLogRow(row.copy(slot = 4), 4))
    }

    @Test
    fun slotZeroIsNotApplicable() {
        val row = parseLogRow("t\tMOVE_TO_FOLDER\tp\t0\tlabel")
        assertFalse(isApplicableLogRow(row!!, 4))
    }

    @Test
    fun nonNumericSlotDoesNotParse() {
        assertNull(parseLogRow("t\tMOVE_TO_FOLDER\tp\tx\tlabel"))
    }

    @Test
    fun lineWithoutTabsDoesNotParse() {
        assertNull(parseLogRow("just a line"))
        assertNull(parseLogRow(""))
    }

    @Test
    fun tooFewOrTooManyFieldsDoNotParse() {
        assertNull(parseLogRow("t\tMOVE_TO_FOLDER\tp\t1"))
        assertNull(parseLogRow("t\tMOVE_TO_FOLDER\tp\t1\tlabel\textra"))
    }

    // --- fileNameFromPath ---

    @Test
    fun windowsPathGivesFileName() {
        assertEquals("Song.mp3", fileNameFromPath("C:\\Users\\me\\Music\\Song.mp3"))
    }

    @Test
    fun androidPathGivesFileName() {
        assertEquals("Song.mp3", fileNameFromPath("/storage/emulated/0/Music/Song.mp3"))
    }

    @Test
    fun mixedSeparatorsGiveFileName() {
        assertEquals("Song.mp3", fileNameFromPath("C:\\Music/Sub\\Song.mp3"))
    }

    @Test
    fun bareNameIsKept() {
        assertEquals("Song.mp3", fileNameFromPath("Song.mp3"))
    }

    // --- profile round trip ---

    @Test
    fun exportThenParseGivesSameValues() {
        val actions =
            mapOf(
                0 to mapOf(1 to WssButtonAction.MOVE_1, 2 to WssButtonAction.NORMAL),
                1 to mapOf(2 to WssButtonAction.MOVE_2, 3 to WssButtonAction.MOOD_3),
                2 to mapOf(1 to WssButtonAction.NOTHING),
            )
        val text =
            exportProfileText(
                labels = listOf("Calm", "Energy", "Focus", "Sleep"),
                moods = listOf("happy", "sad", "calm"),
                situations = listOf("morning", "night"),
                buttonActions = actions,
                multiPressMs = 500,
            )
        val entries = parseProfile(text)
        val single = { key: String ->
            entries.single { it.first == key }.second
        }
        assertEquals("1", single("version"))
        assertEquals("Calm", single("slot.1.label"))
        assertEquals("Energy", single("slot.2.label"))
        assertEquals("Focus", single("slot.3.label"))
        assertEquals("Sleep", single("slot.4.label"))
        assertEquals(3, entries.count { it.first == "mood" })
        assertEquals("happy", entries.filter { it.first == "mood" }[0].second)
        assertEquals("sad", entries.filter { it.first == "mood" }[1].second)
        assertEquals("calm", entries.filter { it.first == "mood" }[2].second)
        assertEquals(2, entries.count { it.first == "situation" })
        assertEquals("MOVE_1", single("button.prev.1"))
        assertEquals("NORMAL", single("button.prev.2"))
        // Keys absent from the test map export as NORMAL.
        assertEquals("NORMAL", single("button.next.1"))
        assertEquals("MOVE_2", single("button.next.2"))
        assertEquals("MOOD_3", single("button.next.3"))
        assertEquals("NOTHING", single("button.play.1"))
        assertEquals("NORMAL", single("button.play.2"))
        assertEquals("500", single("multiPressMs"))
        assertTrue(text.endsWith("\r\n"))
        assertFalse(text.contains("\n\r"))
    }

    @Test
    fun crlfAndLfBothParse() {
        assertEquals(
            parseProfile("version\t1\r\nmood\thappy"),
            parseProfile("version\t1\nmood\thappy"),
        )
    }

    @Test
    fun commentsAndUnknownKeysIgnored() {
        // Unknown keys survive parsing but are ignored by importProfile.
        val entries = parseProfile("# comment\nunknown.key\tvalue\nversion\t1\n\n")
        assertEquals(listOf("unknown.key" to "value", "version" to "1"), entries)
    }

    @Test
    fun emptyValueIsKept() {
        val entries = parseProfile("slot.1.label\t")
        assertEquals(listOf("slot.1.label" to ""), entries)
    }

    // --- parseButtonKey ---

    @Test
    fun validButtonKeyParses() {
        val spec = parseButtonKey("button.next.2", "MOVE_2")
        assertEquals(WeSongSamSettings.KEY_NEXT, spec!!.keyIndex)
        assertEquals(2, spec.presses)
        assertEquals(WssButtonAction.MOVE_2, spec.action)
    }

    @Test
    fun unknownKeyNameRejected() {
        assertNull(parseButtonKey("button.stop.1", "MOVE_1"))
    }

    @Test
    fun pressOutOfRangeRejected() {
        assertNull(parseButtonKey("button.play.4", "MOVE_1"))
        assertNull(parseButtonKey("button.play.0", "MOVE_1"))
    }

    @Test
    fun unknownActionValueRejected() {
        assertNull(parseButtonKey("button.play.1", "SOMETHING"))
    }

    @Test
    fun malformedKeyRejected() {
        assertNull(parseButtonKey("button", "MOVE_1"))
        assertNull(parseButtonKey("button.play", "MOVE_1"))
        assertNull(parseButtonKey("button.play.1.extra", "MOVE_1"))
        assertNull(parseButtonKey("button.play.x", "MOVE_1"))
    }
}
