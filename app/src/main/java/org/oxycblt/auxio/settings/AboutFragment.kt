/*
 * Copyright (c) 2021 Auxio Project
 * AboutFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio.settings

import android.os.Bundle
import android.text.format.Formatter
import android.text.format.DateFormat
import java.util.Date
import android.view.LayoutInflater
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.transition.MaterialFadeThrough
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.BuildConfig
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentAboutBinding
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.playback.formatDurationMs
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.openInBrowser
import org.oxycblt.auxio.util.systemBarInsetsCompat

/**
 * A [ViewBindingFragment] that displays information about the app and the current music library.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class AboutFragment : ViewBindingFragment<FragmentAboutBinding>() {
    private val musicModel: MusicViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialFadeThrough()
        exitTransition = MaterialFadeThrough()
    }

    override fun onCreateBinding(inflater: LayoutInflater) = FragmentAboutBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentAboutBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        // --- UI SETUP ---
        binding.aboutToolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.aboutContents.setOnApplyWindowInsetsListener { view, insets ->
            view.updatePadding(bottom = insets.systemBarInsetsCompat.bottom)
            insets
        }
        binding.aboutVersion.text = BuildConfig.VERSION_NAME
        binding.aboutWiki.setOnClickListener { requireContext().openInBrowser(LINK_WIKI) }
        // VIEWMODEL SETUP
        collectImmediately(musicModel.statistics, ::updateStatistics)
        updateListeningStatistics()
    }

    override fun onResume() {
        super.onResume()
        updateListeningStatistics()
    }

    private fun updateListeningStatistics() {
        val stats = requireContext().getSharedPreferences("notli_listening_stats", 0)
        val listeningMs = stats.getLong("listening_time_ms", 0L)
        val tracksPlayed = stats.getLong("tracks_played", 0L)
        val listeningSessions = stats.getLong("listening_sessions", 0L)
        val todayListeningMs = stats.getLong("daily_listening_time_ms", 0L)
        val lastPlayedAt = stats.getLong("last_played_at", 0L)
        val lastPlayed = if (lastPlayedAt > 0L) DateFormat.getDateTimeInstance().format(Date(lastPlayedAt))
            else getString(R.string.lbl_never_played)
        val binding = requireBinding()
        binding.aboutListeningTime.text =
            getString(R.string.fmt_listening_time, listeningMs.formatDurationMs(false))
        binding.aboutTracksPlayed.text = getString(R.string.fmt_tracks_played, tracksPlayed)
        binding.aboutTodayListening.text =
            getString(R.string.fmt_listening_today, todayListeningMs.formatDurationMs(false))
        binding.aboutListeningSessions.text =
            getString(R.string.fmt_listening_sessions, listeningSessions)
        binding.aboutLastPlayed.text = getString(R.string.fmt_last_played, lastPlayed)
    }

    private fun updateStatistics(statistics: MusicViewModel.Statistics?) {
        val binding = requireBinding()
        binding.aboutSongCount.text = getString(R.string.fmt_lib_song_count, statistics?.songs ?: 0)
        requireBinding().aboutAlbumCount.text =
            getString(R.string.fmt_lib_album_count, statistics?.albums ?: 0)
        requireBinding().aboutArtistCount.text =
            getString(R.string.fmt_lib_artist_count, statistics?.artists ?: 0)
        requireBinding().aboutGenreCount.text =
            getString(R.string.fmt_lib_genre_count, statistics?.genres ?: 0)
        binding.aboutTotalDuration.text =
            getString(
                R.string.fmt_lib_total_duration,
                (statistics?.durationMs ?: 0).formatDurationMs(false),
            )

        binding.aboutTotalSize.text =
            getString(
                R.string.fmt_lib_total_size,
                Formatter.formatFileSize(context, statistics?.totalSizeBytes ?: 0L),
            )
    }
}
