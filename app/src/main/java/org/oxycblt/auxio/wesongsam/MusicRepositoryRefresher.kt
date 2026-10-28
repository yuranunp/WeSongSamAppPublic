/*
 * Copyright (c) 2026 WeSongSam Project
 * MusicRepositoryRefresher.kt is part of WeSongSam.
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
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.oxycblt.auxio.music.MusicRepository
import timber.log.Timber as L

/**
 * [LibraryRefresher] that triggers a music library re-index after a file move, so the moved song
 * disappears from its old location and appears in the new one. Ongoing playback keeps streaming
 * from the old content Uri; the indexer's change tracking reconciles the queue.
 */
@Singleton
class MusicRepositoryRefresher
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
) : LibraryRefresher {
    override fun requestRefresh() {
        L.d("WeSongSam move completed, requesting re-index")
        musicRepository.requestIndex(true)
    }
}
