/*
 * Copyright (c) 2026 WeSongSam Project
 * WeSongSamMomentDialog.kt is part of WeSongSam.
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

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogWssMomentBinding
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.ViewBindingMaterialDialogFragment
import org.oxycblt.auxio.util.showToast
import timber.log.Timber as L

/**
 * A dialog combining "Add by mood" and "Music for the moment": choosing a mood keyword queues all
 * songs whose TXXX:MOOD tag matches, and choosing a situation queues songs whose TXXX:BPM/ENERGY
 * tags fit the situation's energy range. The action is logged for the Windows program.
 */
@AndroidEntryPoint
class WeSongSamMomentDialog : ViewBindingMaterialDialogFragment<DialogWssMomentBinding>() {
    @Inject lateinit var musicRepository: MusicRepository

    @Inject lateinit var wesongsamSettings: WeSongSamSettings

    @Inject lateinit var moodMatcher: WeSongSamMoodMatcher

    @Inject lateinit var actionLog: WeSongSamActionLog

    private val playbackModel: PlaybackViewModel by activityViewModels()

    override fun onCreateBinding(inflater: LayoutInflater) = DialogWssMomentBinding.inflate(inflater)

    override fun onConfigDialog(builder: AlertDialog.Builder) {
        builder.setTitle(R.string.wss_moment_title).setNegativeButton(R.string.lbl_cancel, null)
    }

    @SuppressLint("RestrictedApi")
    override fun onBindingCreated(binding: DialogWssMomentBinding, savedInstanceState: Bundle?) {
        val moodGroup = binding.wssMoodGroup
        for (mood in wesongsamSettings.moods) {
            moodGroup.addView(newChip(mood.name) { onMoodPicked(mood.name) })
        }

        // Built-in situation energy ranges (BPM), shown alongside user-configured situations.
        val situations =
            if (wesongsamSettings.situations.isNotEmpty()) {
                wesongsamSettings.situations.map { it.name }
            } else {
                resources.getStringArray(R.array.wss_default_situations).toList()
            }
        val ranges = situations.map(::situationBpmRange)
        val situationGroup = binding.wssSituationGroup
        situations.forEachIndexed { index, name ->
            situationGroup.addView(newChip(name) { onSituationPicked(name, ranges[index]) })
        }
    }

    private fun newChip(label: String, onClick: () -> Unit): Chip {
        val chip = Chip(requireContext())
        chip.text = label
        chip.isCheckable = false
        chip.isClickable = true
        chip.setOnClickListener { onClick() }
        return chip
    }

    private fun onMoodPicked(mood: String) {
        L.d("Add by mood: $mood")
        val songs = allSongs()
        val matched = moodMatcher.match(songs, mood)
        if (matched.isEmpty()) {
            requireContext().showToast(R.string.wss_no_matches_toast)
            return
        }
        val current = playbackModel.song.value
        if (current != null) {
            actionLog.append(WeSongSamActionLog.ACTION_MOOD, current, null, mood)
        }
        playbackModel.playNext(matched)
        dismiss()
    }

    private fun onSituationPicked(situation: String, range: IntRange) {
        L.d("Music for the moment: $situation")
        val songs = allSongs()
        val matched = moodMatcher.matchEnergy(songs, range)
        if (matched.isEmpty()) {
            requireContext().showToast(R.string.wss_no_matches_toast)
            return
        }
        val current = playbackModel.song.value
        if (current != null) {
            actionLog.append(WeSongSamActionLog.ACTION_MOMENT, current, null, situation)
        }
        playbackModel.shuffle(matched)
        dismiss()
    }

    private fun allSongs() = musicRepository.library?.songs ?: listOf()

    private fun situationBpmRange(situation: String): IntRange {
        return when (situation.lowercase()) {
            "sleep", "นอน" -> 40..70
            "relax", "ผ่อน" -> 60..90
            "focus", "ทำงาน", "อ่าน" -> 90..110
            "walk", "เดิน" -> 100..120
            "dance", "เต้น", "party", "ปาร์ตี้" -> 120..140
            "workout", "ออกกำลัง" -> 140..180
            else -> 70..140
        }
    }
}
