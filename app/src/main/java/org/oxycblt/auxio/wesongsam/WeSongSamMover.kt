/*
 * Copyright (c) 2026 WeSongSam Project
 * WeSongSamMover.kt is part of WeSongSam.
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
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/** Result of a move attempt. */
sealed interface MoveResult {
    /** The file was moved into the target folder. */
    data object Done : MoveResult

    /** The target folder is not configured or cannot be written. */
    data object NoFolder : MoveResult

    /** The source file could not be read or the move failed. */
    data class Error(val message: String?) : MoveResult
}

/**
 * Moves the currently playing song's file into one of the six configured SAF folders using the
 * Storage Access Framework. Playback is unaffected: the song streams from its existing content
 * Uri until the library is re-indexed, and the indexer's change tracking picks up the move
 * afterwards.
 */
@Singleton
class WeSongSamMover
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val wesongsamSettings: WeSongSamSettings,
    private val actionLog: WeSongSamActionLog,
    private val libraryRefresher: LibraryRefresher,
) {
    /**
     * Move [song] into folder number [button] (1-6) and log the action.
     *
     * @param targetFolder Override for the folder name written to the log (e.g. a mood-based
     * destination name). Null to use the configured folder label.
     */
    suspend fun moveToFolder(song: Song, button: Int, targetFolder: String? = null): MoveResult {
        val folder = wesongsamSettings.folders.getOrNull(button - 1)
        val treeUri = folder?.uri
        if (treeUri == null) {
            L.d("No folder configured for button $button")
            return MoveResult.NoFolder
        }
        val result = move(song, treeUri)
        if (result is MoveResult.Done) {
            actionLog.append(
                WeSongSamActionLog.ACTION_MOVE,
                song,
                button,
                targetFolder ?: folder.label,
            )
            libraryRefresher.requestRefresh()
        } else {
            actionLog.append(
                "MOVE_FAILED",
                song,
                button,
                targetFolder ?: folder.label,
            )
        }
        return result
    }

    private fun move(song: Song, treeUri: Uri): MoveResult {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
        if (tree == null || !tree.canWrite()) {
            return MoveResult.NoFolder
        }
        val sourceUri = song.uri
        val name = song.path.name ?: song.uri.lastPathSegment ?: return MoveResult.Error(null)
        return try {
            val displayName = sourceUri.lastPathSegment?.substringAfterLast('/') ?: name
            val existing = tree.findFile(displayName)
            val target =
                existing ?: tree.createFile("audio/*", displayName)
                    ?: return MoveResult.Error("create_failed")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
                    input.copyTo(output)
                } ?: return MoveResult.Error("open_output_failed")
            } ?: return MoveResult.Error("open_input_failed")
            // Best effort delete at the old location.
            try {
                context.contentResolver.delete(sourceUri, null, null)
            } catch (e: Exception) {
                L.d("Could not delete source after copy", e)
            }
            MoveResult.Done
        } catch (e: Exception) {
            L.e("Move failed", e)
            MoveResult.Error(e.message)
        }
    }
}

/** Bridge to request a library re-index from the music system. */
interface LibraryRefresher {
    /** Request an immediate re-index (with cache) of the music library. */
    fun requestRefresh()
}
