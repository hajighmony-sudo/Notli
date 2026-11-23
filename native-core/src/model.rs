use std::fmt;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
pub struct TrackId(pub String);

impl TrackId {
    pub fn new(value: impl Into<String>) -> Result<Self, &'static str> {
        let value = value.into();
        if value.trim().is_empty() || value.len() > 256 { return Err("invalid track id"); }
        Ok(Self(value))
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Track {
    pub id: TrackId,
    pub title: String,
    pub artist: Option<String>,
    pub album: Option<String>,
    /// URI understood by the Android adapter: content://, file://, or approved https://.
    pub source_uri: String,
    pub duration_ms: Option<u64>,
}

impl Track {
    pub fn is_valid(&self) -> bool {
        let id_valid = !self.id.0.trim().is_empty()
            && self.id.0.len() <= 256
            && !self.id.0.chars().any(char::is_control);
        let source_valid = valid_source_uri(&self.source_uri);

        id_valid
            && !self.title.trim().is_empty()
            && self.title.len() <= 1024
            && source_valid
            && self.artist.as_ref().is_none_or(|v| v.len() <= 1024)
            && self.album.as_ref().is_none_or(|v| v.len() <= 1024)
    }
}

fn valid_source_uri(uri: &str) -> bool {
    if uri.is_empty() || uri.len() > 8192 || uri.chars().any(char::is_control) {
        return false;
    }

    let Some((scheme, rest)) = uri.split_once("://") else {
        return false;
    };

    if rest.is_empty() {
        return false;
    }

    match scheme.to_ascii_lowercase().as_str() {
        "content" | "https" => rest
            .split('/')
            .next()
            .is_some_and(|authority| !authority.is_empty()),
        "file" => rest.starts_with('/'),
        _ => false,
    }
}

#[cfg(test)]
mod validation_tests {
    use super::*;

    fn track(id: &str, uri: &str) -> Track {
        Track {
            id: TrackId(id.to_owned()),
            title: "A track".to_owned(),
            artist: None,
            album: None,
            source_uri: uri.to_owned(),
            duration_ms: None,
        }
    }

    #[test]
    fn accepts_android_media_and_approved_remote_uris() {
        assert!(track("1", "content://media/external/audio/1").is_valid());
        assert!(track("2", "file:///storage/music/song.mp3").is_valid());
        assert!(track("3", "https://cdn.example/song.mp3").is_valid());
    }

    #[test]
    fn rejects_unsupported_or_malformed_source_uris() {
        for uri in [
            "",
            "javascript://alert(1)",
            "http://insecure.example/song.mp3",
            "https://",
            "content://",
            "file://relative/path.mp3",
            "https://example.com/song.mp3\n",
        ] {
            assert!(!track("1", uri).is_valid(), "URI should be rejected: {uri:?}");
        }
    }

    #[test]
    fn rejects_invalid_deserialized_track_ids() {
        assert!(!track("  ", "content://media/audio/1").is_valid());
        assert!(!track("bad\nid", "content://media/audio/1").is_valid());
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
pub enum RepeatMode { #[default] Off, One, All }

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
pub enum PlaybackStatus { #[default] Idle, Loading, Playing, Paused, Stopped, Completed, Error }

#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct PlaybackState {
    pub status: PlaybackStatus,
    pub current_track: Option<TrackId>,
    pub position_ms: u64,
    pub duration_ms: Option<u64>,
    pub repeat_mode: RepeatMode,
    pub shuffle_enabled: bool,
    pub is_buffering: bool,
    pub last_error: Option<String>,
    pub revision: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum PlayerCommand {
    SetQueue { tracks: Vec<Track>, start_index: usize, autoplay: bool },
    Play, Pause, Resume, Stop, Seek { position_ms: u64 }, Next, Previous,
    PlayTrack(TrackId), PlayQueueIndex(usize), AddToQueue(Track), PlayNext(Track),
    RemoveFromQueue(TrackId), RemoveQueueItem(usize),
    MoveInQueue { track_id: TrackId, new_index: usize },
    MoveQueueItem { from_index: usize, to_index: usize }, ClearQueue,
    SetRepeatMode(RepeatMode), SetShuffle { enabled: bool, seed: u64 },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum BackendAction { Prepare(Track), Play, Pause, Stop, SeekTo(u64) }

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum BackendEvent {
    Prepared { track_id: TrackId, duration_ms: Option<u64> },
    Position { track_id: TrackId, position_ms: u64 },
    Buffering { track_id: TrackId, is_buffering: bool },
    Ended { track_id: TrackId },
    Failed { track_id: TrackId, message: String },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum PlayerEvent {
    StateChanged(PlaybackState), TrackChanged(Option<TrackId>), QueueChanged,
    PlaybackStarted, PlaybackPaused, PlaybackStopped, PlaybackCompleted, PlaybackError(String),
}

impl fmt::Display for TrackId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result { f.write_str(&self.0) }
}
