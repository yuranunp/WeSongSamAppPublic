/*
 * Copyright (c) 2026 WeSongSam Project
 * WeSongSamSettings.kt is part of WeSongSam.
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
import androidx.core.content.edit
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber as L

/** One of the six move-to-folder destinations. */
data class WssFolder(
    val index: Int,
    /** SAF tree Uri string, or null if unconfigured. */
    val treeUri: String?,
    /** User-facing button label. */
    val label: String,
) {
    val uri: Uri?
        get() = treeUri?.toUri()
}

/** A configured mood keyword used by "Add by mood". */
data class WssMood(val name: String)

/** A configured listening situation used by "Music for the moment". */
data class WssSituation(val name: String)

/**
 * WeSongSam configuration: the six move-to-folder destinations (SAF tree URIs + labels), mood
 * keywords, listening situations, and the location of the actions log file.
 */
interface WeSongSamSettings {
    /** The six configured move-to-folder destinations. */
    val folders: List<WssFolder>

    /** Mood keywords used for "Add by mood". */
    val moods: List<WssMood>

    /** Listening situations used for "Music for the moment". */
    val situations: List<WssSituation>

    /**
     * The location of the WeSongSam_actions.log file. A SAF tree Uri string if the log lives in a
     * user-picked directory, or null to use app-external files.
     */
    val logTreeUri: String?

    fun setFolder(index: Int, treeUri: String?, label: String)

    fun setMoods(names: List<String>)

    fun setSituations(names: List<String>)

    fun setLogTreeUri(treeUri: String?)

    /**
     * Import a WeSongSam_phone.json configuration written by the Windows program.
     *
     * @return True if the file was recognized and applied.
     */
    fun importPhoneJson(json: String): Boolean

    interface Listener {
        /** Called when any WeSongSam configuration changed. */
        fun onWeSongSamSettingsChanged() {}
    }
}

@Singleton
class WeSongSamSettingsImpl
@Inject
constructor(@ApplicationContext private val context: Context) : WeSongSamSettings {
    private val sharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val listeners = mutableListOf<WeSongSamSettings.Listener>()

    override val folders: List<WssFolder>
        get() =
            (0 until FOLDER_COUNT).map { index ->
                val raw = sharedPreferences.getString(keyFolderUri(index), null)
                val fallback =
                    context.getString(FOLDER_FALLBACK_LABELS[index])
                WssFolder(
                    index,
                    raw,
                    sharedPreferences.getString(keyFolderLabel(index), fallback) ?: fallback,
                )
            }

    override val moods: List<WssMood>
        get() = readList(KEY_MOODS).map(::WssMood)

    override val situations: List<WssSituation>
        get() = readList(KEY_SITUATIONS).map(::WssSituation)

    override val logTreeUri: String?
        get() = sharedPreferences.getString(KEY_LOG_TREE_URI, null)

    override fun setFolder(index: Int, treeUri: String?, label: String) {
        sharedPreferences.edit {
            if (treeUri == null) {
                putString(keyFolderUri(index), null)
            } else {
                putString(keyFolderUri(index), treeUri)
            }
            putString(keyFolderLabel(index), label)
            apply()
        }
        notifyChanged()
    }

    override fun setMoods(names: List<String>) {
        writeList(KEY_MOODS, names)
        notifyChanged()
    }

    override fun setSituations(names: List<String>) {
        writeList(KEY_SITUATIONS, names)
        notifyChanged()
    }

    override fun setLogTreeUri(treeUri: String?) {
        sharedPreferences.edit {
            putString(KEY_LOG_TREE_URI, treeUri)
            apply()
        }
        notifyChanged()
    }

    override fun importPhoneJson(json: String): Boolean {
        return try {
            val root = JSONObject(json)
            val foldersJson = root.optJSONArray("folders")
            if (foldersJson != null) {
                sharedPreferences.edit {
                    for (i in 0 until minOf(FOLDER_COUNT, foldersJson.length())) {
                        val entry = foldersJson.optJSONObject(i) ?: continue
                        val label = entry.optString("label", "")
                        if (label.isNotEmpty()) {
                            putString(
                                keyFolderLabel(i),
                                label,
                            )
                        }
                        val uri = entry.optString("uri", "")
                        if (uri.isNotEmpty()) {
                            putString(keyFolderUri(i), uri)
                        }
                    }
                    apply()
                }
            }
            val moodsJson = root.optJSONArray("moods")
            if (moodsJson != null) {
                writeList(
                    KEY_MOODS,
                    (0 until moodsJson.length()).mapNotNull { moodsJson.optString(it).ifEmpty { null } },
                )
            }
            val situationsJson = root.optJSONArray("situations")
            if (situationsJson != null) {
                writeList(
                    KEY_SITUATIONS,
                    (0 until situationsJson.length())
                        .mapNotNull { situationsJson.optString(it).ifEmpty { null } },
                )
            }
            // Windows-side mood/situation tags are written as TXXX:MOOD / TXXX:BPM /
            // TXXX:ENERGY, and the json may also carry the raw keyword lists.
            val logUri = root.optString("logUri", "")
            if (logUri.isNotEmpty()) {
                sharedPreferences.edit {
                    putString(KEY_LOG_TREE_URI, logUri)
                    apply()
                }
            }
            notifyChanged()
            true
        } catch (e: JSONException) {
            L.e("Invalid WeSongSam_phone.json", e)
            false
        }
    }

    fun addListener(listener: WeSongSamSettings.Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: WeSongSamSettings.Listener) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        for (listener in listeners.toList()) {
            listener.onWeSongSamSettingsChanged()
        }
    }

    private fun readList(key: String): List<String> {
        val raw = sharedPreferences.getString(key, null) ?: return listOf()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optString(it).ifEmpty { null } }
        } catch (e: JSONException) {
            listOf()
        }
    }

    private fun writeList(key: String, values: List<String>) {
        val array = JSONArray()
        values.forEach { array.put(it) }
        sharedPreferences.edit {
            putString(key, array.toString())
            apply()
        }
        notifyChanged()
    }

    private fun keyFolderUri(index: Int) = "wesongsam_folder_${index}_uri"

    private fun keyFolderLabel(index: Int) = "wesongsam_folder_${index}_label"

    companion object {
        private const val PREFS_FILE = "wesongsam_settings"
        private const val KEY_MOODS = "wesongsam_moods"
        private const val KEY_SITUATIONS = "wesongsam_situations"
        private const val KEY_LOG_TREE_URI = "wesongsam_log_tree_uri"

        /** Number of move-to-folder destinations/buttons. */
        const val FOLDER_COUNT = 6

        private val FOLDER_FALLBACK_LABELS =
            intArrayOf(
                org.oxycblt.auxio.R.string.wss_folder_1,
                org.oxycblt.auxio.R.string.wss_folder_2,
                org.oxycblt.auxio.R.string.wss_folder_3,
                org.oxycblt.auxio.R.string.wss_folder_4,
                org.oxycblt.auxio.R.string.wss_folder_5,
                org.oxycblt.auxio.R.string.wss_folder_6,
            )
    }
}
