/*
 * Copyright (c) 2021 Auxio Project
 * PlaybackBarFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback

import android.os.Bundle
import android.view.LayoutInflater
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.databinding.FragmentPlaybackBarBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.ui.ViewBindingFragment

/**
 * Compose-based compact playback surface.
 *
 * Playback, media-session, queue, repeat, and shuffle behavior remain owned by the existing
 * PlaybackViewModel and service.
 */
@AndroidEntryPoint
class PlaybackBarFragment : ViewBindingFragment<FragmentPlaybackBarBinding>() {
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentPlaybackBarBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentPlaybackBarBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.playbackCompose.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val song by playbackModel.song.collectAsStateWithLifecycle()
                val isPlaying by playbackModel.isPlaying.collectAsStateWithLifecycle()
                val positionDs by playbackModel.positionDs.collectAsStateWithLifecycle()
                val actionMode by playbackModel.currentBarAction.collectAsStateWithLifecycle()
                val repeatMode by playbackModel.repeatMode.collectAsStateWithLifecycle()
                val isShuffled by playbackModel.isShuffled.collectAsStateWithLifecycle()
                val context = androidx.compose.ui.platform.LocalContext.current

                NotliComposeTheme {
                    NotliPlaybackBar(
                        song = song,
                        title = song?.name?.resolve(context).orEmpty(),
                        artist = song?.artists?.resolveNames(context).orEmpty(),
                        isPlaying = isPlaying,
                        positionDs = positionDs,
                        actionMode = actionMode,
                        repeatMode = repeatMode,
                        isShuffled = isShuffled,
                        onOpen = { playbackModel.openPlayback() },
                        onShowAlbum = { song?.let(detailModel::showAlbum) },
                        onTogglePlaying = { playbackModel.togglePlaying() },
                        onNext = { playbackModel.next() },
                        onToggleRepeat = { playbackModel.toggleRepeatMode() },
                        onToggleShuffle = { playbackModel.toggleShuffled() },
                    )
                }
            }
        }
    }
}
