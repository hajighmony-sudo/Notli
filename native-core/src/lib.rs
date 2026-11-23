//! Notli's platform-independent music engine.
//! Rust owns playback decisions, queue invariants, state transitions and download-task state.
//! Android provides Media3 audio output, MediaStore access, networking and OS integration.

pub mod downloads;
pub mod jni_bridge;
pub mod engine;
pub mod model;
pub mod queue;

pub use downloads::{DownloadCommand, DownloadError, DownloadManager, DownloadRequest, DownloadSnapshot, DownloadStatus, MAX_DOWNLOAD_TASKS};
pub use engine::{CoreError, CoreOutput, PlayerCore, PlayerSnapshot, SNAPSHOT_SCHEMA_VERSION};
pub use model::{BackendAction, BackendEvent, PlaybackState, PlaybackStatus, PlayerCommand, PlayerEvent, RepeatMode, Track, TrackId};
pub use queue::{PlaybackQueue, QueueError, QueueSnapshot, MAX_QUEUE_LEN};
