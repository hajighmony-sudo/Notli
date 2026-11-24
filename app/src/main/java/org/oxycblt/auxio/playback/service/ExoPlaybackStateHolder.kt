/*
 * Copyright (c) 2024 Auxio Project
 * ExoPlaybackStateHolder.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.service

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.oxycblt.auxio.image.ImageSettings
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.playback.PlaybackSettings
import org.oxycblt.auxio.playback.nativecore.RustMedia3Bridge
import org.oxycblt.auxio.playback.persist.PersistenceRepository
import org.oxycblt.auxio.playback.replaygain.ReplayGainAudioProcessor
import org.oxycblt.auxio.playback.state.DeferredPlayback
import org.oxycblt.auxio.playback.state.PlaybackCommand
import org.oxycblt.auxio.playback.state.PlaybackStateHolder
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.Progression
import org.oxycblt.auxio.playback.state.RawQueue
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.playback.state.ShuffleMode
import org.oxycblt.auxio.playback.state.StateAck
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber as L

@OptIn(UnstableApi::class)
class ExoPlaybackStateHolder(
    private val context: Context,
    private val player: ExoPlayer,
    private val playbackManager: PlaybackStateManager,
    private val persistenceRepository: PersistenceRepository,
    private val playbackSettings: PlaybackSettings,
    private val commandFactory: PlaybackCommand.Factory,
    private val replayGainProcessor: ReplayGainAudioProcessor,
    private val musicRepository: MusicRepository,
    private val imageSettings: ImageSettings,
) :
    PlaybackStateHolder,
    Player.Listener,
    MusicRepository.UpdateListener,
    PlaybackSettings.Listener,
    ImageSettings.Listener {
    private val rustBridge = RustMedia3Bridge(player)
    private val saveJob = Job()
    private val saveScope = CoroutineScope(Dispatchers.IO + saveJob)
    private val restoreScope = CoroutineScope(Dispatchers.IO + saveJob)
    private var currentSaveJob: Job? = null
    private var openAudioEffectSession = false
    private var listeningStartedAtElapsedMs: Long? = null
    private var audioEffects: PlaybackAudioEffects? = null

    var sessionOngoing = false
        private set

    fun attach() {
        playbackManager.registerStateHolder(this)
        musicRepository.addUpdateListener(this)
        player.addListener(this)
        replayGainProcessor.attach()
        playbackSettings.registerListener(this)
        imageSettings.registerListener(this)
    }

    fun release() {
        persistListeningTime(force = true)
        listeningStartedAtElapsedMs = null
        audioEffects?.release()
        audioEffects = null
        rustBridge.close()
        saveJob.cancel()
        playbackManager.unregisterStateHolder(this)
        musicRepository.removeUpdateListener(this)
        player.removeListener(this)
        replayGainProcessor.release()
        imageSettings.unregisterListener(this)
        playbackSettings.unregisterListener(this)
        player.release()
    }

    override var parent: MusicParent? = null
        private set

    override val progression: Progression
        get() {
            val mediaItem = player.currentMediaItem ?: return Progression.nil()
            val duration = mediaItem.mediaMetadata.extras?.getLong("durationMs") ?: Long.MAX_VALUE
            val clampedPosition = player.currentPosition.coerceAtLeast(0).coerceAtMost(duration)
            return Progression.from(player.playWhenReady, player.isPlaying, clampedPosition)
        }

    override val repeatMode
        get() =
            when (val repeatMode = player.repeatMode) {
                Player.REPEAT_MODE_OFF -> RepeatMode.NONE
                Player.REPEAT_MODE_ONE -> RepeatMode.TRACK
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> throw IllegalStateException("Unknown repeat mode: $repeatMode")
            }

    override val audioSessionId: Int
        get() = player.audioSessionId

    override fun resolveQueue(): RawQueue {
        if (musicRepository.library == null) {
            // No library, cannot do anything.
            return RawQueue(emptyList(), emptyList(), 0)
        }
        val heap = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        val shuffledMapping =
            if (player.shuffleModeEnabled) {
                player.unscrambleQueueIndices()
            } else {
                emptyList()
            }
        return RawQueue(heap.mapNotNull { it.song }, shuffledMapping, player.currentMediaItemIndex)
    }

    override fun handleDeferred(action: DeferredPlayback): Boolean {
        val library =
            musicRepository.library?.takeIf { !it.empty() }
                // No library, cannot do anything.
                ?: return false

        when (action) {
            // Restore state -> Start a new restoreState job
            is DeferredPlayback.RestoreState -> {
                L.d("Restoring playback state")
                restoreScope.launch {
                    val state = persistenceRepository.readState()
                    withContext(Dispatchers.Main) {
                        if (state != null) {
                            // Apply the saved state on the main thread to prevent code expecting
                            // state updates on the main thread from crashing.
                            playbackManager.applySavedState(state, false)
                            if (action.play) {
                                playbackManager.playing(true)
                            }
                        } else if (action.fallback != null) {
                            playbackManager.playDeferred(action.fallback)
                        }
                    }
                }
            }
            // Shuffle all -> Start new playback from all songs
            is DeferredPlayback.ShuffleAll -> {
                L.d("Shuffling all tracks")
                playbackManager.play(
                    requireNotNull(commandFactory.all(ShuffleMode.ON)) {
                        "Invalid playback parameters"
                    },
                )
            }
            // Open -> Try to find the Song for the given file and then play it from all songs
            is DeferredPlayback.Open -> {
                L.d("Opening specified file")
                context.applicationContext.contentResolver
                    .query(
                        action.uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                        null,
                        null,
                        null,
                    )
                    ?.use { cursor ->
                        val displayNameIndex =
                            cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            val displayName = cursor.getString(displayNameIndex)
                            val size = cursor.getLong(sizeIndex)
                            val song =
                                library.songs.find {
                                    it.path.name == displayName && it.size == size
                                }
                            if (song != null) {
                                val command =
                                    requireNotNull(
                                        commandFactory.songFromAll(song, ShuffleMode.IMPLICIT),
                                    ) {
                                        "Invalid playback command"
                                    }
                                playbackManager.play(command)
                            }
                        }
                    }
            }
        }

        return true
    }

    override fun playing(playing: Boolean) {
        if (playing) rustBridge.play() else rustBridge.pause()
    }

    override fun seekTo(positionMs: Long) {
        rustBridge.seek(positionMs)
        deferSave()
        // Ack handled w/ExoPlayer events
    }

    override fun repeatMode(repeatMode: RepeatMode) {
        player.repeatMode =
            when (repeatMode) {
                RepeatMode.NONE -> Player.REPEAT_MODE_OFF
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.TRACK -> Player.REPEAT_MODE_ONE
            }
        rustBridge.setRepeatMode(
            when (repeatMode) {
                RepeatMode.NONE -> "Off"
                RepeatMode.ALL -> "All"
                RepeatMode.TRACK -> "One"
            },
        )
        updatePauseOnRepeat()
        playbackManager.ack(this, StateAck.RepeatModeChanged)
        deferSave()
    }

    override fun newPlayback(command: PlaybackCommand) {
        parent = command.parent
        player.shuffleModeEnabled = command.shuffled
        player.setMediaItems(command.queue.map { it.buildMediaItem() })
        val startIndex =
            command.song
                ?.let { command.queue.indexOf(it) }
                .also { check(it != -1) { "Start song not in queue" } }
        if (command.shuffled) {
            player.setShuffleOrder(BetterShuffleOrder(command.queue.size, startIndex ?: -1))
        }
        val target = startIndex ?: player.currentTimeline.getFirstWindowIndex(command.shuffled)
        player.seekTo(target, C.TIME_UNSET)
        val rawQueue = resolveQueue()
        rustBridge.setQueue(
            rawQueue.resolveSongs(),
            rawQueue.resolveIndex(),
            true,
            rawQueue.isShuffled,
        )
        playbackManager.ack(this, StateAck.NewPlayback)
        deferSave()
    }

    override fun shuffled(shuffled: Boolean) {
        player.setShuffleModeEnabled(shuffled)
        if (player.shuffleModeEnabled) {
            // Have to manually refresh the shuffle seed and anchor it to the new current songs
            player.setShuffleOrder(
                BetterShuffleOrder(player.mediaItemCount, player.currentMediaItemIndex),
            )
        }
        val rawQueue = resolveQueue()
        rustBridge.synchronizeVisibleQueue(
            rawQueue.resolveSongs(),
            rawQueue.resolveIndex(),
            player.playWhenReady,
            player.currentPosition,
            rawQueue.isShuffled,
        )
        playbackManager.ack(this, StateAck.QueueReordered)
        deferSave()
    }

    override fun next() {
        val shouldRemainPaused = playbackSettings.rememberPause && !player.playWhenReady
        rustBridge.next()
        if (shouldRemainPaused) rustBridge.pause()
        playbackManager.ack(this, StateAck.IndexMoved)
        deferSave()
    }

    override fun prev() {
        val shouldRemainPaused = playbackSettings.rememberPause && !player.playWhenReady
        rustBridge.previous(playbackSettings.rewindWithPrev)
        if (shouldRemainPaused) rustBridge.pause()
        playbackManager.ack(this, StateAck.IndexMoved)
        deferSave()
    }

    override fun goto(index: Int) {
        val indices = player.unscrambleQueueIndices()
        if (indices.isEmpty()) {
            return
        }

        val shouldRemainPaused = playbackSettings.rememberPause && !player.playWhenReady
        val song = player.getMediaItemAt(indices[index]).song ?: return
        rustBridge.playTrack(song)
        if (shouldRemainPaused) rustBridge.pause()
        playbackManager.ack(this, StateAck.IndexMoved)
        deferSave()
    }

    override fun playNext(songs: List<Song>, ack: StateAck.PlayNext) {
        val currTimeline = player.currentTimeline
        val nextIndex =
            if (currTimeline.isEmpty) {
                C.INDEX_UNSET
            } else {
                currTimeline.getNextWindowIndex(
                    player.currentMediaItemIndex,
                    Player.REPEAT_MODE_OFF,
                    player.shuffleModeEnabled,
                )
            }

        if (nextIndex == C.INDEX_UNSET) {
            player.addMediaItems(songs.map { it.buildMediaItem() })
        } else {
            player.addMediaItems(nextIndex, songs.map { it.buildMediaItem() })
        }
        rustBridge.playNext(songs)
        playbackManager.ack(this, ack)
        deferSave()
    }

    override fun addToQueue(songs: List<Song>, ack: StateAck.AddToQueue) {
        player.addMediaItems(songs.map { it.buildMediaItem() })
        rustBridge.addToQueue(songs)
        playbackManager.ack(this, ack)
        deferSave()
    }

    override fun move(from: Int, to: Int, ack: StateAck.Move) {
        val indices = player.unscrambleQueueIndices()
        if (indices.isEmpty()) {
            return
        }

        val trueFrom = indices[from]
        val trueTo = indices[to]
        // ExoPlayer does not actually update it's ShuffleOrder when moving items. Retain a
        // semblance of "normalcy" by doing a weird no-op swap that actually moves the item.
        when {
            trueFrom > trueTo -> {
                player.moveMediaItem(trueFrom, trueTo)
                player.moveMediaItem(trueTo + 1, trueFrom)
            }
            trueTo > trueFrom -> {
                player.moveMediaItem(trueFrom, trueTo)
                player.moveMediaItem(trueTo - 1, trueFrom)
            }
        }
        rustBridge.move(from, to)
        playbackManager.ack(this, ack)
        deferSave()
    }

    override fun remove(at: Int, ack: StateAck.Remove) {
        val indices = player.unscrambleQueueIndices()
        if (indices.isEmpty()) {
            return
        }

        val trueIndex = indices[at]
        val songWillChange = player.currentMediaItemIndex == trueIndex
        player.removeMediaItem(trueIndex)
        rustBridge.remove(at)
        if (songWillChange && !playbackSettings.rememberPause) {
            rustBridge.play()
        }
        playbackManager.ack(this, ack)
        deferSave()
    }

    override fun applySavedState(
        parent: MusicParent?,
        rawQueue: RawQueue,
        positionMs: Long,
        repeatMode: RepeatMode,
        ack: StateAck.NewPlayback?,
    ) {
        var sendNewPlaybackEvent = false
        var shouldSeek = false
        if (this.parent != parent) {
            this.parent = parent
            sendNewPlaybackEvent = true
        }
        if (rawQueue != resolveQueue()) {
            player.setMediaItems(rawQueue.heap.map { it.buildMediaItem() })
            if (rawQueue.isShuffled) {
                player.shuffleModeEnabled = true
                player.setShuffleOrder(BetterShuffleOrder(rawQueue.shuffledMapping.toIntArray()))
            } else {
                player.shuffleModeEnabled = false
            }
            player.seekTo(rawQueue.heapIndex, C.TIME_UNSET)
            player.prepare()
            player.pause()
            sendNewPlaybackEvent = true
            shouldSeek = true
        }

        repeatMode(repeatMode)
        // See if we differ by more than a second. This allows us to avoid a meaningless seek
        // in the case of a "tight restore" (i.e music was reloaded).
        // In the case that this is a false positive, it's not very percievable (at least compared
        // to skipping when updating the library).
        // TODO: Introduce a better state management system rather than do something finicky like
        // this.
        if (shouldSeek || abs(player.currentPosition - positionMs) > 1000L) {
            player.seekTo(positionMs)
        }
        rustBridge.synchronizeVisibleQueue(
            rawQueue.resolveSongs(),
            rawQueue.resolveIndex(),
            false,
            positionMs,
            rawQueue.isShuffled,
        )

        if (sendNewPlaybackEvent) {
            ack?.let { playbackManager.ack(this, it) }
        }
    }

    override fun endSession() {
        // This session has ended, so we need to reset this flag for when the next
        // session starts.
        playbackManager.playing(false)
        save {
            // User could feasibly start playing again if they were fast enough, so
            // we need to avoid stopping the foreground state if that's the case.
            if (!playbackManager.progression.isPlaying) {
                sessionOngoing = false
                playbackManager.ack(this, StateAck.SessionEnded)
            }
        }
    }

    override fun reset(ack: StateAck.NewPlayback) {
        player.setMediaItems(listOf())
        rustBridge.clearQueue()
        playbackManager.ack(this, ack)
        deferSave()
    }

    // --- PLAYER OVERRIDES ---

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        super.onPlayWhenReadyChanged(playWhenReady, reason)

        if (player.playWhenReady) {
            // Mark that we have started playing so that the notification can now be posted.
            L.d("Player has started playing")
            sessionOngoing = true
            if (!openAudioEffectSession) {
                // Convention to start an audioeffect session on play/pause rather than
                // start/stop
                L.d("Opening audio effect session")
                broadcastAudioEffectAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                openAudioEffectSession = true
            }
        } else if (openAudioEffectSession) {
            // Make sure to close the audio session when we stop playback.
            L.d("Closing audio effect session")
            broadcastAudioEffectAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            openAudioEffectSession = false
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        super.onIsPlayingChanged(isPlaying)
        if (isPlaying) {
            if (audioEffects == null) {
                audioEffects = PlaybackAudioEffects(context, player)
            }
            if (listeningStartedAtElapsedMs == null) {
                val stats =
                    context.getSharedPreferences(LISTENING_STATS_PREFS, Context.MODE_PRIVATE)
                stats
                    .edit()
                    .putLong(KEY_LISTENING_SESSIONS, stats.getLong(KEY_LISTENING_SESSIONS, 0L) + 1L)
                    .apply()
                listeningStartedAtElapsedMs = SystemClock.elapsedRealtime()
            }
        } else {
            persistListeningTime(force = true)
            listeningStartedAtElapsedMs = null
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        rustBridge.onPlaybackStateChanged(playbackState)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        super.onMediaItemTransition(mediaItem, reason)
        rustBridge.onMediaItemTransition(reason)
        if (mediaItem != null && player.playWhenReady) {
            val stats = context.getSharedPreferences(LISTENING_STATS_PREFS, Context.MODE_PRIVATE)
            stats
                .edit()
                .putLong(KEY_TRACKS_PLAYED, stats.getLong(KEY_TRACKS_PLAYED, 0L) + 1L)
                .putLong(KEY_LAST_PLAYED_AT, System.currentTimeMillis())
                .apply()
        }

        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playbackManager.ack(this, StateAck.IndexMoved)
            deferSave()
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        super.onEvents(player, events)
        persistListeningTime()

        // So many actions trigger progression changes that it becomes easier just to handle it
        // in an ExoPlayer callback anyway. This doesn't really cause issues anywhere.
        if (
            events.containsAny(
                Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
            )
        ) {
            rustBridge.onPositionChanged()
        }

        if (
            events.containsAny(
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_POSITION_DISCONTINUITY,
            )
        ) {
            L.d("Player state changed, must synchronize state")
            playbackManager.ack(this, StateAck.ProgressionChanged)
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        L.e(error, "Player error occurred")
        rustBridge.onPlayerError(error.message ?: error.errorCodeName)
    }

    private fun persistListeningTime(force: Boolean = false) {
        val startedAt = listeningStartedAtElapsedMs ?: return
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - startedAt
        if (elapsed < 1_000L || (!force && elapsed < LISTENING_SAVE_INTERVAL_MS)) return

        val stats = context.getSharedPreferences(LISTENING_STATS_PREFS, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val storedDay = stats.getString(KEY_DAILY_LISTENING_DATE, null)
        val todayTotal =
            if (storedDay == today) stats.getLong(KEY_DAILY_LISTENING_TIME_MS, 0L) else 0L
        stats
            .edit()
            .putLong(KEY_LISTENING_TIME_MS, stats.getLong(KEY_LISTENING_TIME_MS, 0L) + elapsed)
            .putLong(KEY_DAILY_LISTENING_TIME_MS, todayTotal + elapsed)
            .putString(KEY_DAILY_LISTENING_DATE, today)
            .apply()
        listeningStartedAtElapsedMs = now
    }

    private fun broadcastAudioEffectAction(event: String) {
        L.d("Broadcasting AudioEffect event: $event")
        context.sendBroadcast(
            Intent(event)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC),
        )
    }

    // --- MUSICREPOSITORY METHODS ---

    override fun onMusicChanges(changes: MusicRepository.Changes) {
        if (changes.deviceLibrary && musicRepository.library?.takeIf { !it.empty() } != null) {
            // We now have a library, see if we have anything we need to do.
            L.d("Library obtained, requesting action")
            playbackManager.requestAction(this)
        }
    }

    // --- PLAYBACKSETTINGS OVERRIDES ---

    override fun onPauseOnRepeatChanged() {
        super.onPauseOnRepeatChanged()
        updatePauseOnRepeat()
    }

    private fun updatePauseOnRepeat() {
        player.pauseAtEndOfMediaItems =
            player.repeatMode == Player.REPEAT_MODE_ONE && playbackSettings.pauseOnRepeat
    }

    private fun save(cb: () -> Unit) {
        saveJob {
            if (sessionOngoing) {
                persistenceRepository.saveState(playbackManager.toSavedState())
            }
            withContext(Dispatchers.Main) { cb() }
        }
    }

    private fun deferSave() {
        saveJob {
            L.d("Waiting for save buffer")
            delay(SAVE_BUFFER.milliseconds)
            yield()
            L.d("Committing saved state")
            if (sessionOngoing) {
                persistenceRepository.saveState(playbackManager.toSavedState())
            }
        }
    }

    private fun saveJob(block: suspend () -> Unit) {
        currentSaveJob?.let {
            L.d("Discarding prior save job")
            it.cancel()
        }
        currentSaveJob = saveScope.launch { block() }
    }

    private fun Song.buildMediaItem() = MediaItem.Builder().setUri(uri).setTag(this).build()

    private val MediaItem.song: Song?
        get() = this.localConfiguration?.tag as? Song?

    private fun Player.unscrambleQueueIndices(): List<Int> {
        val timeline = currentTimeline
        if (timeline.isEmpty) {
            return emptyList()
        }
        val queue = mutableListOf<Int>()

        // Add the active queue item.
        val currentMediaItemIndex = currentMediaItemIndex
        queue.add(currentMediaItemIndex)

        // Fill queue alternating with next and/or previous queue items.
        var firstMediaItemIndex = currentMediaItemIndex
        var lastMediaItemIndex = currentMediaItemIndex
        val shuffleModeEnabled = shuffleModeEnabled
        while ((firstMediaItemIndex != C.INDEX_UNSET || lastMediaItemIndex != C.INDEX_UNSET)) {
            // Begin with next to have a longer tail than head if an even sized queue needs to be
            // trimmed.
            if (lastMediaItemIndex != C.INDEX_UNSET) {
                lastMediaItemIndex =
                    timeline.getNextWindowIndex(
                        lastMediaItemIndex,
                        Player.REPEAT_MODE_OFF,
                        shuffleModeEnabled,
                    )
                if (lastMediaItemIndex != C.INDEX_UNSET) {
                    queue.add(lastMediaItemIndex)
                }
            }
            if (firstMediaItemIndex != C.INDEX_UNSET) {
                firstMediaItemIndex =
                    timeline.getPreviousWindowIndex(
                        firstMediaItemIndex,
                        Player.REPEAT_MODE_OFF,
                        shuffleModeEnabled,
                    )
                if (firstMediaItemIndex != C.INDEX_UNSET) {
                    queue.add(0, firstMediaItemIndex)
                }
            }
        }

        return queue
    }

    class Factory
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val playbackManager: PlaybackStateManager,
        private val persistenceRepository: PersistenceRepository,
        private val playbackSettings: PlaybackSettings,
        private val commandFactory: PlaybackCommand.Factory,
        private val mediaSourceFactory: MediaSource.Factory,
        private val replayGainProcessor: ReplayGainAudioProcessor,
        private val musicRepository: MusicRepository,
        private val imageSettings: ImageSettings,
    ) {
        fun create(): ExoPlaybackStateHolder {
            // Since Auxio is a music player, only specify an audio renderer to save
            // battery/apk size/cache size]
            val audioRenderer = RenderersFactory { handler, _, audioListener, _, _ ->
                arrayOf<BaseRenderer>(
                    FfmpegAudioRenderer(handler, audioListener, replayGainProcessor),
                    MediaCodecAudioRenderer(
                        context,
                        MediaCodecSelector.DEFAULT,
                        handler,
                        audioListener,
                        DefaultAudioSink.Builder(context)
                            .setAudioProcessors(arrayOf(replayGainProcessor))
                            .build(),
                    ),
                )
            }

            val exoPlayer =
                ExoPlayer.Builder(context, audioRenderer)
                    .setMediaSourceFactory(mediaSourceFactory)
                    // Enable automatic WakeLock support
                    .setWakeMode(C.WAKE_MODE_LOCAL)
                    .setAudioAttributes(
                        // Signal that we are a music player.
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(),
                        true,
                    )
                    .build()

            return ExoPlaybackStateHolder(
                context,
                exoPlayer,
                playbackManager,
                persistenceRepository,
                playbackSettings,
                commandFactory,
                replayGainProcessor,
                musicRepository,
                imageSettings,
            )
        }
    }

    private companion object {
        const val SAVE_BUFFER = 5000L
        const val LISTENING_STATS_PREFS = "notli_listening_stats"
        const val KEY_LISTENING_TIME_MS = "listening_time_ms"
        const val KEY_TRACKS_PLAYED = "tracks_played"
        const val KEY_LISTENING_SESSIONS = "listening_sessions"
        const val KEY_DAILY_LISTENING_TIME_MS = "daily_listening_time_ms"
        const val KEY_DAILY_LISTENING_DATE = "daily_listening_date"
        const val KEY_LAST_PLAYED_AT = "last_played_at"
        const val LISTENING_SAVE_INTERVAL_MS = 15_000L
    }
}
