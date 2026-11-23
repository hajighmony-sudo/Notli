/*
 * Copyright (c) 2026 Auxio Project.
 * RustMedia3Bridge.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.oxycblt.auxio.playback.nativecore

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import org.json.JSONObject
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.tag.Name
import timber.log.Timber as L

/**
 * Connects Rust's playback domain to the live Media3 player.
 *
 * Rust owns queue navigation and emits typed backend actions. Media3 remains responsible for
 * decoding and audio output. Android Song objects are resolved by their stable Music UID.
 */
internal class RustMedia3Bridge(
    private val player: ExoPlayer,
    private val engine: RustMusicEngine = RustMusicEngine.create(),
) : AutoCloseable {
    private var activeTrackId: String? = null

    @Synchronized
    fun setQueue(
        songs: List<Song>,
        startIndex: Int,
        autoplay: Boolean,
        shuffleEnabled: Boolean = false,
    ) {
        val tracks = JSONArray()
        songs.forEach { tracks.put(it.toRustTrack()) }
        val command =
            JSONObject().put(
                "SetQueue",
                JSONObject()
                    .put("tracks", tracks)
                    .put("start_index", startIndex)
                    .put("autoplay", autoplay),
            )
        dispatch(command)
        if (shuffleEnabled) {
            // The list passed to Rust is already in Media3's visible playback order.
            dispatch(JSONObject().put("SetShuffleFlag", true))
        }
    }

    @Synchronized
    fun play() = dispatchUnit("Play")

    @Synchronized
    fun pause() = dispatchUnit("Pause")

    @Synchronized
    fun stop() = dispatchUnit("Stop")

    @Synchronized
    fun seek(positionMs: Long) =
        dispatch(
            JSONObject().put(
                "Seek",
                JSONObject().put("position_ms", positionMs.coerceAtLeast(0L)),
            ),
        )

    @Synchronized
    fun next() = dispatchUnit("Next")

    @Synchronized
    fun previous(rewindIfLate: Boolean) =\n        dispatch(\n            JSONObject().put(\n                "PreviousWithRewind",\n                JSONObject().put("rewind_if_late", rewindIfLate),\n            ),\n        )

    @Synchronized
    fun playTrack(song: Song) =
        dispatch(JSONObject().put("PlayTrack", song.uid.toString()))

    @Synchronized
    fun addToQueue(songs: List<Song>) {
        songs.forEach { dispatch(JSONObject().put("AddToQueue", it.toRustTrack())) }
    }

    @Synchronized
    fun playNext(songs: List<Song>) {
        // Rust inserts each new item directly after the cursor, so reverse the batch to retain
        // the caller's original order in the resulting queue.
        songs.asReversed().forEach { dispatch(JSONObject().put("PlayNext", it.toRustTrack())) }
    }

    @Synchronized
    fun move(from: Int, to: Int) {
        dispatch(
            JSONObject().put(
                "MoveQueueItem",
                JSONObject().put("from_index", from).put("to_index", to),
            ),
        )
    }

    @Synchronized
    fun remove(index: Int) =
        dispatch(JSONObject().put("RemoveQueueItem", index))

    @Synchronized
    fun clearQueue() = dispatchUnit("ClearQueue")

    @Synchronized
    fun setRepeatMode(mode: String) =
        dispatch(JSONObject().put("SetRepeatMode", mode))

    /**
     * Media3 supplies the visible queue order (including its shuffle mapping), so Rust does not
     * generate a second permutation that could disagree with the actual playlist.
     */
    @Synchronized
    fun synchronizeVisibleQueue(
        songs: List<Song>,
        currentIndex: Int,
        autoplay: Boolean,
        positionMs: Long,
        shuffleEnabled: Boolean,
    ) {
        setQueue(
            songs,
            currentIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0)),
            autoplay,
            shuffleEnabled,
        )
        if (songs.isNotEmpty()) seek(positionMs)
    }

    @Synchronized
    fun onPlaybackStateChanged(state: Int) {
        val trackId = currentTrackId() ?: activeTrackId ?: return
        when (state) {
            Player.STATE_BUFFERING ->
                backendEvent(
                    JSONObject().put(
                        "Buffering",
                        JSONObject().put("track_id", trackId).put("is_buffering", true),
                    ),
                )
            Player.STATE_READY -> {
                val duration = player.duration
                backendEvent(
                    JSONObject().put(
                        "Prepared",
                        JSONObject()
                            .put("track_id", trackId)
                            .put(
                                "duration_ms",
                                if (duration != C.TIME_UNSET && duration >= 0L) duration
                                else JSONObject.NULL,
                            ),
                    ),
                )
                backendEvent(
                    JSONObject().put(
                        "Buffering",
                        JSONObject().put("track_id", trackId).put("is_buffering", false),
                    ),
                )
            }
            Player.STATE_ENDED ->
                backendEvent(
                    JSONObject().put(
                        "Ended",
                        JSONObject().put("track_id", activeTrackId ?: trackId),
                    ),
                )
        }
    }

    @Synchronized
    fun onMediaItemTransition(reason: Int) {
        val nextId = currentTrackId() ?: return
        val previousId = activeTrackId
        if (
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                previousId != null &&
                previousId != nextId
        ) {
            backendEvent(
                JSONObject().put("Ended", JSONObject().put("track_id", previousId)),
            )
        }
        activeTrackId = nextId
    }

    @Synchronized
    fun onPositionChanged() {
        val trackId = currentTrackId() ?: return
        backendEvent(
            JSONObject().put(
                "Position",
                JSONObject()
                    .put("track_id", trackId)
                    .put("position_ms", player.currentPosition.coerceAtLeast(0L)),
            ),
        )
    }

    @Synchronized
    fun onPlayerError(message: String) {
        val trackId = currentTrackId() ?: activeTrackId ?: return
        backendEvent(
            JSONObject().put(
                "Failed",
                JSONObject().put("track_id", trackId).put("message", message.take(4096)),
            ),
        )
    }

    @Synchronized
    override fun close() {
        engine.close()
        activeTrackId = null
    }

    private fun currentTrackId(): String? =
        (player.currentMediaItem?.localConfiguration?.tag as? Song)?.uid?.toString()

    private fun Name.asText(): String =
        when (this) {
            is Name.Known -> raw
            is Name.Unknown -> placeholder.name
        }

    private fun Song.toRustTrack(): JSONObject =
        JSONObject()
            .put("id", uid.toString())
            .put("title", name.raw.take(1024).ifBlank { uri.lastPathSegment ?: uid.toString() })
            .put(
                "artist",
                artists.joinToString(", ") { it.name.asText() }
                    .take(1024)
                    .takeIf { it.isNotBlank() }
                    ?: JSONObject.NULL,
            )
            .put(
                "album",
                album.name.asText().take(1024).takeIf { it.isNotBlank() } ?: JSONObject.NULL,
            )
            .put("source_uri", uri.toString())
            .put("duration_ms", if (durationMs > 0L) durationMs else JSONObject.NULL)

    private fun dispatchUnit(command: String) {
        executeReply(engine.dispatch(JSONObject.quote(command)))
    }

    private fun dispatch(command: JSONObject) {
        executeReply(engine.dispatch(command.toString()))
    }

    private fun backendEvent(event: JSONObject) {
        executeReply(engine.onBackendEvent(event.toString()))
    }

    private fun executeReply(reply: String) {
        val response =
            try {
                JSONObject(reply)
            } catch (error: Exception) {
                L.e(error, "Rust music core returned malformed JSON")
                return
            }
        if (!response.optBoolean("ok")) {
            L.e(
                "Rust music core rejected a live playback operation: %s",
                response.optString("error"),
            )
            return
        }
        val output = response.optJSONObject("value") ?: return
        val actions = output.optJSONArray("actions") ?: return
        for (index in 0 until actions.length()) {
            when (val action = actions.opt(index)) {
                "Play" -> player.play()
                "Pause" -> player.pause()
                "Stop" -> player.stop()
                is JSONObject -> {
                    when {
                        action.has("Prepare") -> prepareTrack(action.optJSONObject("Prepare"))
                        action.has("SeekTo") ->
                            player.seekTo(action.optLong("SeekTo").coerceAtLeast(0L))
                    }
                }
            }
        }
    }

    private fun prepareTrack(track: JSONObject?) {
        val id = track?.optString("id")?.takeIf { it.isNotBlank() } ?: return
        val targetIndex =
            (0 until player.mediaItemCount).firstOrNull { index ->
                (player.getMediaItemAt(index).localConfiguration?.tag as? Song)?.uid?.toString() == id
            }
        if (targetIndex == null) {
            L.e("Rust selected a track that is not present in the Media3 playlist: %s", id)
            return
        }
        activeTrackId = id
        if (player.currentMediaItemIndex != targetIndex) {
            player.seekTo(targetIndex, C.TIME_UNSET)
        }
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
            player.prepare()
        }
    }
}
