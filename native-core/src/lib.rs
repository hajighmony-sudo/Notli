//! Notli's platform-independent music engine.
//! Rust owns playback decisions, queue invariants, state transitions and download-task state.
//! Android provides Media3 audio output, MediaStore access, networking and OS integration.

pub mod downloads;
pub mod engine;
pub mod model;
pub mod queue;

pub use downloads::{DownloadError, DownloadManager, DownloadRequest, DownloadSnapshot, DownloadStatus};
pub use engine::{CoreError, CoreOutput, PlayerCore, PlayerSnapshot};
pub use model::{BackendAction, BackendEvent, PlaybackState, PlaybackStatus, PlayerCommand, PlayerEvent, RepeatMode, Track, TrackId};
pub use queue::{PlaybackQueue, QueueError, QueueSnapshot};
