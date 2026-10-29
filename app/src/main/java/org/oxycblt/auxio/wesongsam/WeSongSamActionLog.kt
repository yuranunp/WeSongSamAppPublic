/*
 * Copyright (c) 2026 WeSongSam Project
 * WeSongSamActionLog.kt is part of WeSongSam.
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
import java.io.BufferedWriter
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * Appends every WeSongSam action to WeSongSam_actions_android.log as TSV rows:
 *
 * ```
 * <time>\t<action>\t<source relative path>\t<button 1-4>\t<target folder>
 * ```
 *
 * The log is written to the user-configured log directory (SAF tree Uri) when configured, or
 * otherwise into the app's external files directory. "Apply move log…" in the Windows plug-in
 * consumes the file.
 */
@Singleton
class WeSongSamActionLog
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val wesongsamSettings: WeSongSamSettings,
) {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun append(action: String, song: Song?, button: Int?, targetFolder: String?) {
        try {
            val time = dateFormat.format(Date())
            val source = song?.path?.toString().orEmpty()
            val row =
                listOf(time, action, source, button?.toString().orEmpty(), targetFolder.orEmpty())
                    .joinToString("\t") { field -> field.replace('\t', ' ').replace('\n', ' ') }
            writeRow(row)
            L.d("Logged action: $row")
        } catch (e: Exception) {
            L.e("Failed to write WeSongSam action log", e)
        }
    }

    private fun writeRow(row: String) {
        val logTree = wesongsamSettings.logTreeUri
        if (logTree != null) {
            try {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(logTree))
                if (tree != null && tree.canWrite()) {
                    val logFile =
                        tree.findFile(LOG_FILE_NAME) ?: tree.createFile("text/plain", LOG_FILE_NAME)
                    if (logFile != null) {
                        context.contentResolver.openOutputStream(logFile.uri, "wa")?.use { out ->
                            BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { w ->
                                w.write(row)
                                w.write("\r\n")
                            }
                        }
                        return
                    }
                }
            } catch (e: Exception) {
                L.e("Log tree write failed, falling back to app dir", e)
            }
        }
        // Fallback: app-specific external files directory.
        val dir = context.getExternalFilesDir(null) ?: return
        FileOutputStream(dir.resolve(LOG_FILE_NAME), true).use { out ->
            BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { w ->
                w.write(row)
                w.write("\r\n")
            }
        }
    }

    companion object {
        const val LOG_FILE_NAME = "WeSongSam_actions_android.log"

        /** Action names used in the log. */
        const val ACTION_MOVE = "MOVE_TO_FOLDER"
        const val ACTION_MOOD = "ADD_BY_MOOD"
        const val ACTION_MOMENT = "MUSIC_FOR_MOMENT"
    }
}
