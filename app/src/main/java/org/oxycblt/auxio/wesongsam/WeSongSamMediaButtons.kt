/*
 * Copyright (c) 2026 WeSongSam Project
 * WeSongSamMediaButtons.kt is part of WeSongSam.
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

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import javax.inject.Inject
import javax.inject.Singleton
import org.oxycblt.auxio.playback.state.PlaybackStateManager

/**
 * Turns presses of the physical media buttons (wired headset, Bluetooth, car) into WeSongSam
 * actions, e.g. Previous once = move to folder 1, Next twice = move to folder 2. A key whose
 * press patterns all keep their normal job is left to the media session, so it has no delay.
 * On-screen and notification buttons do not come through here and keep their normal job.
 */
@Singleton
class WeSongSamMediaButtons
@Inject
constructor(
    private val settings: WeSongSamSettings,
    private val actionHandler: WeSongSamActionHandler,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val flushRunnable = Runnable { flush() }
    private var pendingKey = -1
    private var presses = 0
    private var playbackManager: PlaybackStateManager? = null

    /** @return True if WeSongSam took this key event, false to let the media session handle it. */
    fun onKeyEvent(event: KeyEvent, playbackManager: PlaybackStateManager): Boolean {
        val key =
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> WeSongSamSettings.KEY_PREV
                KeyEvent.KEYCODE_MEDIA_NEXT -> WeSongSamSettings.KEY_NEXT
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> WeSongSamSettings.KEY_PLAY
                else -> return false
            }
        // The most presses that do something special; none means the key keeps its normal job.
        val maxPresses =
            (WeSongSamSettings.MAX_PRESSES downTo 1).firstOrNull {
                settings.buttonAction(key, it) != WssButtonAction.NORMAL
            } ?: return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) {
            return true
        }
        if (key != pendingKey) {
            flush()
        }
        handler.removeCallbacks(flushRunnable)
        pendingKey = key
        presses++
        this.playbackManager = playbackManager
        if (presses >= maxPresses) {
            flush()
        } else {
            handler.postDelayed(flushRunnable, settings.multiPressMs.toLong())
        }
        return true
    }

    private fun flush() {
        handler.removeCallbacks(flushRunnable)
        val key = pendingKey
        val count = presses
        val manager = playbackManager
        pendingKey = -1
        presses = 0
        playbackManager = null
        if (key < 0 || manager == null) {
            return
        }
        when (val action = settings.buttonAction(key, count)) {
            WssButtonAction.NORMAL -> repeat(count) { normal(key, manager) }
            else -> actionHandler.run(action, manager)
        }
    }

    private fun normal(key: Int, manager: PlaybackStateManager) {
        when (key) {
            WeSongSamSettings.KEY_PREV -> manager.prev()
            WeSongSamSettings.KEY_NEXT -> manager.next()
            else -> manager.playing(!manager.progression.isPlaying)
        }
    }
}
