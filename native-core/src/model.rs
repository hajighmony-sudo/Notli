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
        !self.title.trim().is_empty() && self.title.len() <= 1024
            && !self.source_uri.trim().is_empty() && self.source_uri.len() <= 8192
            && self.artist.as_ref().is_none_or(|v| v.len() <= 1024)
            && self.album.as_ref().is_none_or(|v| v.len() <= 1024)
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
