/*
 * Copyright (c) 2026 WeSongSam Project
 * WeSongSamActionHandler.kt is part of WeSongSam.
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

import android.content.Context
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * Handles the WeSongSam move-to-folder actions (buttons 1-4) coming from the playback screen, the
 * media notification, and the home widget. Moves the currently playing song's file, logs the
 * action, and triggers a library refresh; playback itself is never interrupted.
 */
@Singleton
class WeSongSamActionHandler
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val mover: WeSongSamMover,
    private val actionLog: WeSongSamActionLog,
    private val wesongsamSettings: WeSongSamSettings,
    private val moodMatcher: WeSongSamMoodMatcher,
    private val musicRepository: MusicRepository,
) {
    private val scope = CoroutineScope(Dispatchers.Default)

    /** Handle a move-to-folder button press (1-4). */
    fun moveToFolder(button: Int, playbackManager: PlaybackStateManager) {
        val song = playbackManager.currentSong
        if (song == null) {
            L.d("No current song, ignoring move action $button")
            return
        }
        if (wesongsamSettings.folders.getOrNull(button - 1)?.treeUri == null) {
            postResult(MoveResult.NoFolder, button)
            return
        }
        // The re-index after a move drops the moved song from the queue, and playback stops if it
        // is still the current song, so go on to the next song first.
        playbackManager.next()
        scope.launch {
            val result = mover.moveToFolder(song, button)
            postResult(result, button)
        }
    }

    /** Run a media-button action chosen in the WeSongSam settings. */
    fun run(action: WssButtonAction, playbackManager: PlaybackStateManager) {
        when (action) {
            WssButtonAction.MOVE_1 -> moveToFolder(1, playbackManager)
            WssButtonAction.MOVE_2 -> moveToFolder(2, playbackManager)
            WssButtonAction.MOVE_3 -> moveToFolder(3, playbackManager)
            WssButtonAction.MOVE_4 -> moveToFolder(4, playbackManager)
            WssButtonAction.MOOD_1 -> addToMoodPlaylist(1, playbackManager)
            WssButtonAction.MOOD_2 -> addToMoodPlaylist(2, playbackManager)
            WssButtonAction.MOOD_3 -> addToMoodPlaylist(3, playbackManager)
            WssButtonAction.NORMAL,
            WssButtonAction.NOTHING -> {}
        }
    }

    /**
     * Add the playing song to the playlist named after mood button [button] (1-3), creating the
     * playlist if it does not exist yet.
     */
    fun addToMoodPlaylist(button: Int, playbackManager: PlaybackStateManager) {
        val song = playbackManager.currentSong ?: return
        val name = wesongsamSettings.moodButtonNames[button - 1]
        scope.launch {
            val library = musicRepository.library ?: return@launch
            val playlist = library.playlists.find { it.name.raw == name }
            val message =
                when {
                    playlist == null -> {
                        musicRepository.createPlaylist(name, listOf(song))
                        R.string.wss_mood_added_toast
                    }
                    song in playlist.songs -> R.string.wss_mood_already_toast
                    else -> {
                        musicRepository.addToPlaylist(listOf(song), playlist)
                        R.string.wss_mood_added_toast
                    }
                }
            actionLog.append(WeSongSamActionLog.ACTION_MOOD, song, null, name)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(message, name), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Handle an "add by mood" action for the given mood keyword. */
    fun addByMood(mood: String, playbackManager: PlaybackStateManager) {
        val song = playbackManager.currentSong ?: return
        // Matching songs are enqueued by the UI layer (WeSongSamMoodMatcher users); here we
        // only log the action so the Windows program can import it.
        actionLog.append(WeSongSamActionLog.ACTION_MOOD, song, null, mood)
    }

    /** Handle a "music for the moment" action for the given situation. */
    fun musicForMoment(situation: String, playbackManager: PlaybackStateManager) {
        val song = playbackManager.currentSong ?: return
        actionLog.append(WeSongSamActionLog.ACTION_MOMENT, song, null, situation)
    }

    private fun postResult(result: MoveResult, button: Int) {
        scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
            val message =
                when (result) {
                    is MoveResult.Done ->
                        context.getString(
                            R.string.wss_moved_toast,
                            wesongsamSettings.folders.getOrNull(button - 1)?.label ?: "",
                        )
                    is MoveResult.NoFolder -> context.getString(R.string.wss_no_folder_toast)
                    is MoveResult.Error ->
                        context.getString(R.string.wss_move_failed_toast, result.message ?: "")
                }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Finds songs matching a mood/situation keyword using the WeSongSam TXXX tags (MOOD/BPM/ENERGY)
 * parsed from the audio files.
 */
@Singleton
class WeSongSamMoodMatcher
@Inject
constructor() {
    /** Songs whose mood tags contain [keyword] (case-insensitive), or all songs if empty. */
    fun match(songs: Collection<Song>, keyword: String): List<Song> {
        val needle = keyword.trim().lowercase()
        if (needle.isEmpty()) return songs.toList()
        return songs.filter { song ->
            song.wssMoods.any { mood -> mood.lowercase().contains(needle) }
        }
    }

    /** Songs whose BPM falls within a suitable range for the moment. */
    fun matchEnergy(songs: Collection<Song>, bpmRange: IntRange): List<Song> {
        return songs.filter { song ->
            val bpm = song.wssBpm ?: return@filter false
            bpm in bpmRange
        }
    }
}
