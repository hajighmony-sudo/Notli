use serde::{Deserialize, Serialize};
use crate::model::{BackendAction, BackendEvent, PlaybackState, PlaybackStatus, PlayerCommand, PlayerEvent, Track};
use crate::queue::{PlaybackQueue, QueueError, QueueSnapshot};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CoreError { Queue(QueueError), EmptyQueue, NoCurrentTrack, InvalidSnapshot, UnsupportedSnapshotVersion(u32) }
impl From<QueueError> for CoreError { fn from(value: QueueError) -> Self { Self::Queue(value) } }

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct CoreOutput { pub actions: Vec<BackendAction>, pub events: Vec<PlayerEvent> }

/// Schema version written into every [`PlayerSnapshot`]. Bump it whenever the persisted shape
/// changes incompatibly; [`PlayerCore::restore`] rejects snapshots from a newer schema.
pub const SNAPSHOT_SCHEMA_VERSION: u32 = 1;

fn legacy_snapshot_version() -> u32 { 1 }

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PlayerSnapshot {
    /// Snapshots written before versioning existed have no field and are treated as version 1.
    #[serde(default = "legacy_snapshot_version")]
    pub version: u32,
    pub state: PlaybackState,
    pub queue: QueueSnapshot,
}

#[derive(Debug, Default)]
pub struct PlayerCore { state: PlaybackState, queue: PlaybackQueue }

impl PlayerCore {
    pub fn new() -> Self { Self::default() }
    pub fn state(&self) -> &PlaybackState { &self.state }
    pub fn queue(&self) -> &PlaybackQueue { &self.queue }
    pub fn snapshot(&self) -> PlayerSnapshot {
        PlayerSnapshot { version: SNAPSHOT_SCHEMA_VERSION, state: self.state.clone(), queue: self.queue.snapshot() }
    }

    pub fn restore(snapshot: PlayerSnapshot) -> Result<Self, CoreError> {
        if snapshot.version == 0 || snapshot.version > SNAPSHOT_SCHEMA_VERSION {
            return Err(CoreError::UnsupportedSnapshotVersion(snapshot.version));
        }
        let queue = PlaybackQueue::restore(snapshot.queue)?;
        if let Some(id) = &snapshot.state.current_track {
            if queue.current().map(|track| &track.id) != Some(id) {
                return Err(CoreError::InvalidSnapshot);
            }
        } else if !queue.is_empty()
            || snapshot.state.position_ms != 0
            || snapshot.state.is_buffering
            || snapshot.state.status != PlaybackStatus::Idle
        {
            return Err(CoreError::InvalidSnapshot);
        }

        let has_track = snapshot.state.current_track.is_some();
        let state_requires_track = matches!(
            snapshot.state.status,
            PlaybackStatus::Loading
                | PlaybackStatus::Playing
                | PlaybackStatus::Paused
                | PlaybackStatus::Stopped
                | PlaybackStatus::Completed
                | PlaybackStatus::Error
        );
        if snapshot.state.shuffle_enabled != queue.shuffle_enabled()
            || snapshot.state.duration_ms.is_some_and(|duration| snapshot.state.position_ms > duration)
            || snapshot.state.last_error.as_ref().is_some_and(|message| message.len() > 4096)
            || state_requires_track != has_track
            || (snapshot.state.is_buffering && snapshot.state.status != PlaybackStatus::Loading)
            || (!has_track && snapshot.state.duration_ms.is_some())
            || (snapshot.state.status == PlaybackStatus::Error && snapshot.state.last_error.is_none())
        {
            return Err(CoreError::InvalidSnapshot);
        }

        Ok(Self { state: snapshot.state, queue })
    }

    pub fn handle(&mut self, command: PlayerCommand) -> Result<CoreOutput, CoreError> {
        let mut out = CoreOutput::default();
        match command {
            PlayerCommand::SetQueue { tracks, start_index, autoplay } => {
                self.queue.replace(tracks, start_index)?;
                // Replacing the queue resets its order to library order, so mirror that in the
                // state; otherwise a later snapshot would fail the shuffle consistency check.
                self.state.shuffle_enabled = self.queue.shuffle_enabled();
                if let Some(track) = self.queue.current().cloned() { self.select_track(track, autoplay, &mut out); }
                else { self.reset(); }
                out.events.push(PlayerEvent::QueueChanged);
            }
            PlayerCommand::Play | PlayerCommand::Resume => {
                let track = self.queue.current().cloned().ok_or(CoreError::EmptyQueue)?;
                if self.state.status == PlaybackStatus::Stopped || self.state.current_track.as_ref() != Some(&track.id) {
                    self.select_track(track, true, &mut out);
                } else {
                    self.state.status = PlaybackStatus::Playing;
                    out.actions.push(BackendAction::Play);
                    out.events.push(PlayerEvent::PlaybackStarted);
                }
            }
            PlayerCommand::Pause => if self.state.status == PlaybackStatus::Playing || self.state.status == PlaybackStatus::Loading {
                self.state.status = PlaybackStatus::Paused;
                out.actions.push(BackendAction::Pause);
                out.events.push(PlayerEvent::PlaybackPaused);
            },
            PlayerCommand::Stop => if self.state.current_track.is_some() {
                self.state.status = PlaybackStatus::Stopped;
                self.state.position_ms = 0;
                out.actions.push(BackendAction::Stop);
                out.events.push(PlayerEvent::PlaybackStopped);
            },
            PlayerCommand::Seek { position_ms } => {
                if self.state.current_track.is_none() { return Err(CoreError::NoCurrentTrack); }
                let position = self.state.duration_ms.map_or(position_ms, |duration| position_ms.min(duration));
                self.state.position_ms = position;
                out.actions.push(BackendAction::SeekTo(position));
            }
            PlayerCommand::Next => {
                if self.queue.is_empty() {
                    return Err(CoreError::EmptyQueue);
                }
                // A manual skip advances even in Repeat One mode. At the end of a
                // non-repeating queue, mirror Notli's existing player behavior:
                // return to the first item and leave playback paused.
                let repeat = if self.state.repeat_mode == crate::model::RepeatMode::One {
                    crate::model::RepeatMode::Off
                } else {
                    self.state.repeat_mode
                };
                if let Some(track) = self.queue.next(repeat).cloned() {
                    self.select_track(track, true, &mut out);
                } else {
                    let track = self.queue.select_index(0)?.clone();
                    self.select_track(track, false, &mut out);
                }
            }
            PlayerCommand::Previous => {
                if self.state.position_ms > 3000 {
                    self.state.position_ms = 0;
                    out.actions.push(BackendAction::SeekTo(0));
                } else if let Some(track) = self.queue.previous(self.state.repeat_mode).cloned() {
                    self.select_track(track, true, &mut out);
                } else if self.state.current_track.is_some() {
                    // At the first item, Previous rewinds rather than wrapping.
                    self.state.position_ms = 0;
                    out.actions.push(BackendAction::SeekTo(0));
                }
            }
            PlayerCommand::PlayTrack(id) => {
                let track = self.queue.select(&id)?.clone();
                self.select_track(track, true, &mut out);
            }
            PlayerCommand::PlayQueueIndex(index) => {
                let track = self.queue.select_index(index)?.clone();
                self.select_track(track, true, &mut out);
            }
            PlayerCommand::AddToQueue(track) => { self.queue.enqueue(track)?; out.events.push(PlayerEvent::QueueChanged); }
            PlayerCommand::PlayNext(track) => { self.queue.enqueue_next(track)?; out.events.push(PlayerEvent::QueueChanged); }
            PlayerCommand::RemoveFromQueue(id) => {
                let was_current = self.queue.remove(&id)?;
                if was_current {
                    if let Some(track) = self.queue.current().cloned() { self.select_track(track, self.state.status == PlaybackStatus::Playing, &mut out); }
                    else { self.reset(); out.actions.push(BackendAction::Stop); }
                }
                out.events.push(PlayerEvent::QueueChanged);
            }
            PlayerCommand::RemoveQueueItem(index) => {
                let was_current = self.queue.remove_at(index)?;
                if was_current {
                    if let Some(track) = self.queue.current().cloned() { self.select_track(track, self.state.status == PlaybackStatus::Playing, &mut out); }
                    else { self.reset(); out.actions.push(BackendAction::Stop); }
                }
                out.events.push(PlayerEvent::QueueChanged);
            }
            PlayerCommand::MoveInQueue { track_id, new_index } => { self.queue.move_track(&track_id, new_index)?; out.events.push(PlayerEvent::QueueChanged); }
            PlayerCommand::MoveQueueItem { from_index, to_index } => {
                self.queue.move_at(from_index, to_index)?;
                out.events.push(PlayerEvent::QueueChanged);
            }
            PlayerCommand::ClearQueue => { self.queue = PlaybackQueue::default(); self.reset(); out.actions.push(BackendAction::Stop); out.events.push(PlayerEvent::QueueChanged); }
            PlayerCommand::SetRepeatMode(mode) => self.state.repeat_mode = mode,
            PlayerCommand::SetShuffle { enabled, seed } => {
                self.queue.set_shuffle(enabled, seed);
                self.state.shuffle_enabled = enabled;
                out.events.push(PlayerEvent::QueueChanged);
            }
        }
        self.state.revision = self.state.revision.saturating_add(1);
        out.events.push(PlayerEvent::StateChanged(self.state.clone()));
        Ok(out)
    }

    pub fn handle_backend_event(&mut self, event: BackendEvent) -> CoreOutput {
        let event_track_id = match &event {
            BackendEvent::Prepared { track_id, .. }
            | BackendEvent::Position { track_id, .. }
            | BackendEvent::Buffering { track_id, .. }
            | BackendEvent::Ended { track_id }
            | BackendEvent::Failed { track_id, .. } => track_id,
        };
        // Media3 callbacks can arrive after a newer track has already been selected.
        // Ignore stale callbacks completely, including revision changes and notifications.
        if self.state.current_track.as_ref() != Some(event_track_id) {
            return CoreOutput::default();
        }

        let mut out = CoreOutput::default();
        match event {
            BackendEvent::Prepared { track_id, duration_ms } if self.state.current_track.as_ref() == Some(&track_id) => {
                self.state.duration_ms = duration_ms;
                self.state.is_buffering = false;
                if self.state.status == PlaybackStatus::Loading { self.state.status = PlaybackStatus::Playing; }
            }
            BackendEvent::Position { track_id, position_ms } if self.state.current_track.as_ref() == Some(&track_id) => {
                self.state.position_ms = self.state.duration_ms.map_or(position_ms, |duration| position_ms.min(duration));
            }
            BackendEvent::Buffering { track_id, is_buffering } if self.state.current_track.as_ref() == Some(&track_id) => self.state.is_buffering = is_buffering,
            BackendEvent::Ended { track_id } if self.state.current_track.as_ref() == Some(&track_id) => {
                if let Some(track) = self.queue.next(self.state.repeat_mode).cloned() {
                    self.select_track(track, true, &mut out);
                } else if !self.queue.is_empty() {
                    // Match the existing Android player: reaching the end with Repeat Off
                    // returns to the first queue item and pauses there.
                    let track = self.queue.select_index(0).expect("non-empty queue").clone();
                    self.select_track(track, false, &mut out);
                } else {
                    self.state.status = PlaybackStatus::Completed;
                    self.state.position_ms = self.state.duration_ms.unwrap_or(self.state.position_ms);
                    out.events.push(PlayerEvent::PlaybackCompleted);
                }
            }
            BackendEvent::Failed { track_id, message } if self.state.current_track.as_ref() == Some(&track_id) => {
                self.state.status = PlaybackStatus::Error;
                let message: String = message.chars().take(4096).collect();
                self.state.last_error = Some(message.clone());
                out.events.push(PlayerEvent::PlaybackError(message));
            }
            _ => unreachable!("backend event track was validated above"),
        }
        self.state.revision = self.state.revision.wrapping_add(1);
        out.events.push(PlayerEvent::StateChanged(self.state.clone()));
        out
    }

    fn select_track(&mut self, track: Track, autoplay: bool, out: &mut CoreOutput) {
        let changed = self.state.current_track.as_ref() != Some(&track.id);
        self.state.current_track = Some(track.id.clone());
        self.state.position_ms = 0;
        self.state.duration_ms = track.duration_ms;
        self.state.status = if autoplay { PlaybackStatus::Loading } else { PlaybackStatus::Paused };
        self.state.is_buffering = autoplay;
        self.state.last_error = None;
        out.actions.push(BackendAction::Prepare(track));
        if autoplay { out.actions.push(BackendAction::Play); }
        if changed { out.events.push(PlayerEvent::TrackChanged(self.state.current_track.clone())); }
    }

    fn reset(&mut self) {
        self.state = PlaybackState { repeat_mode: self.state.repeat_mode, revision: self.state.revision, ..PlaybackState::default() };
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{RepeatMode, TrackId};

    fn track(id: &str) -> Track {
        Track { id: TrackId::new(id).unwrap(), title: format!("Track {id}"), artist: None, album: None, source_uri: format!("content://music/{id}"), duration_ms: Some(60_000) }
    }
    #[test]
    fn stale_backend_events_do_not_change_state_or_revision() {
        let mut core = PlayerCore::new();
        core.handle(PlayerCommand::SetQueue {
            tracks: vec![track("a"), track("b")],
            start_index: 1,
            autoplay: true,
        }).unwrap();
        let revision = core.state().revision;

        let output = core.handle_backend_event(BackendEvent::Position {
            track_id: TrackId::new("a").unwrap(),
            position_ms: 45_000,
        });

        assert!(output.events.is_empty());
        assert_eq!(core.state().revision, revision);
        assert_eq!(core.state().position_ms, 0);
    }

    #[test]
    fn restore_rejects_inconsistent_shuffle_and_position_state() {
        let mut core = PlayerCore::new();
        core.handle(PlayerCommand::SetQueue {
            tracks: vec![track("a")],
            start_index: 0,
            autoplay: false,
        }).unwrap();
        let mut snapshot = core.snapshot();
        snapshot.state.position_ms = 70_000;
        assert_eq!(PlayerCore::restore(snapshot).err(), Some(CoreError::InvalidSnapshot));

        let mut snapshot = core.snapshot();
        snapshot.state.shuffle_enabled = true;
        assert_eq!(PlayerCore::restore(snapshot).err(), Some(CoreError::InvalidSnapshot));
    }

    #[test]
    fn replacing_queue_while_shuffled_keeps_snapshot_restorable() {
        let mut core = PlayerCore::new();
        core.handle(PlayerCommand::SetQueue { tracks: vec![track("a"), track("b"), track("c")], start_index: 0, autoplay: false }).unwrap();
        core.handle(PlayerCommand::SetShuffle { enabled: true, seed: 42 }).unwrap();
        assert!(core.state().shuffle_enabled);

        core.handle(PlayerCommand::SetQueue { tracks: vec![track("x"), track("y")], start_index: 1, autoplay: true }).unwrap();

        assert!(!core.state().shuffle_enabled);
        let restored = PlayerCore::restore(core.snapshot()).expect("snapshot must restore");
        assert_eq!(restored.snapshot(), core.snapshot());
    }

    #[test]
    fn shuffled_snapshot_round_trips_and_keeps_playing_the_same_track() {
        let mut core = PlayerCore::new();
        core.handle(PlayerCommand::SetQueue { tracks: vec![track("a"), track("b"), track("c"), track("d")], start_index: 2, autoplay: false }).unwrap();
        core.handle(PlayerCommand::SetShuffle { enabled: true, seed: 9 }).unwrap();

        let restored = PlayerCore::restore(core.snapshot()).unwrap();
        assert_eq!(restored.queue().current().unwrap().id.0, "c");
        assert!(restored.state().shuffle_enabled);
    }

    #[test]
    fn snapshots_carry_a_schema_version_and_reject_unknown_ones() {
        let core = PlayerCore::new();
        let mut snapshot = core.snapshot();
        assert_eq!(snapshot.version, SNAPSHOT_SCHEMA_VERSION);
        assert!(PlayerCore::restore(snapshot.clone()).is_ok());

        snapshot.version = SNAPSHOT_SCHEMA_VERSION + 1;
        assert_eq!(
            PlayerCore::restore(snapshot.clone()).err(),
            Some(CoreError::UnsupportedSnapshotVersion(SNAPSHOT_SCHEMA_VERSION + 1))
        );
        snapshot.version = 0;
        assert_eq!(PlayerCore::restore(snapshot).err(), Some(CoreError::UnsupportedSnapshotVersion(0)));
    }

    #[test]
    fn failed_track_can_be_skipped_with_next() {
        let mut core = PlayerCore::new();
        core.handle(PlayerCommand::SetQueue { tracks: vec![track("a"), track("b")], start_index: 0, autoplay: true }).unwrap();
        core.handle_backend_event(BackendEvent::Failed { track_id: TrackId::new("a").unwrap(), message: "codec".into() });
        assert_eq!(core.state().status, PlaybackStatus::Error);

        core.handle(PlayerCommand::Next).unwrap();
        assert_eq!(core.queue().current().unwrap().id.0, "b");
        assert_eq!(core.state().status, PlaybackStatus::Loading);
        assert_eq!(core.state().last_error, None);
    }

    #[test]
    fn removing_the_current_track_advances_and_keeps_playing() {
        let mut core = PlayerCore::new();
        core.handle(PlayerCommand::SetQueue { tracks: vec![track("a"), track("b"), track("c")], start_index: 1, autoplay: true }).unwrap();
        core.handle_backend_event(BackendEvent::Prepared { track_id: TrackId::new("b").unwrap(), duration_ms: Some(60_000) });
        assert_eq!(core.state().status, PlaybackStatus::Playing);

        let out = core.handle(PlayerCommand::RemoveQueueItem(1)).unwrap();
        assert_eq!(core.queue().current().unwrap().id.0, "c");
        assert!(out.actions.contains(&BackendAction::Prepare(track("c"))));
        assert!(out.actions.contains(&BackendAction::Play));
    }

    #[test]
    fn restore_rejects_idle_state_with_a_current_track() {
        let mut core = PlayerCore::new();
        core.handle(PlayerCommand::SetQueue {
            tracks: vec![track("a")],
            start_index: 0,
            autoplay: false,
        }).unwrap();
        let mut snapshot = core.snapshot();
        snapshot.state.status = PlaybackStatus::Idle;
        assert_eq!(
            PlayerCore::restore(snapshot).err(),
            Some(CoreError::InvalidSnapshot)
        );
    }

    #[test]
    fn restore_rejects_error_state_without_error_details() {
        let mut core = PlayerCore::new();
        core.handle(PlayerCommand::SetQueue {
            tracks: vec![track("a")],
            start_index: 0,
            autoplay: false,
        }).unwrap();
        let mut snapshot = core.snapshot();
        snapshot.state.status = PlaybackStatus::Error;
        snapshot.state.last_error = None;
        assert_eq!(
            PlayerCore::restore(snapshot).err(),
            Some(CoreError::InvalidSnapshot)
        );
    }

    #[test]
    fn backend_error_messages_are_bounded_before_publishing() {
        let mut core = PlayerCore::new();
        core.handle(PlayerCommand::SetQueue {
            tracks: vec![track("a")],
            start_index: 0,
            autoplay: true,
        }).unwrap();
        let output = core.handle_backend_event(BackendEvent::Failed {
            track_id: TrackId::new("a").unwrap(),
            message: "x".repeat(10_000),
        });
        assert_eq!(core.state().last_error.as_ref().unwrap().len(), 4096);
        assert!(matches!(
            output.events.last(),
            Some(PlayerEvent::StateChanged(state)) if state.last_error.as_ref().unwrap().len() == 4096
        ));
    }

    #[test]
    fn invalid_queue_does_not_destroy_current_queue() {
        let mut core = PlayerCore::new();
        core.handle(PlayerCommand::SetQueue { tracks: vec![track("a")], start_index: 0, autoplay: false }).unwrap();
        assert!(core.handle(PlayerCommand::SetQueue { tracks: vec![track("x")], start_index: 4, autoplay: true }).is_err());
        assert_eq!(core.queue().current().unwrap().id.0, "a");
    }
    #[test]
    fn manual_next_wraps_to_first_item_paused_when_repeat_is_off() {
        let mut core = PlayerCore::new();
        core.handle(PlayerCommand::SetQueue {
            tracks: vec![track("a"), track("b")],
            start_index: 1,
            autoplay: true,
        }).unwrap();

        let output = core.handle(PlayerCommand::Next).unwrap();

        assert_eq!(core.queue().current().unwrap().id.0, "a");
        assert_eq!(core.state().status, PlaybackStatus::Paused);
        assert!(output.actions.contains(&BackendAction::Prepare(track("a"))));
        assert!(!output.actions.contains(&BackendAction::Play));
    }

    #[test]
    fn manual_next_advances_in_repeat_one_mode() {
        let mut core = PlayerCore::new();
        core.handle(PlayerCommand::SetQueue {
            tracks: vec![track("a"), track("b")],
            start_index: 0,
            autoplay: true,
        }).unwrap();
        core.handle(PlayerCommand::SetRepeatMode(RepeatMode::One)).unwrap();

        core.handle(PlayerCommand::Next).unwrap();

        assert_eq!(core.queue().current().unwrap().id.0, "b");
    }

    #[test]
    fn natural_end_returns_to_first_item_paused_when_repeat_is_off() {
        let mut core = PlayerCore::new();
        core.handle(PlayerCommand::SetQueue {
            tracks: vec![track("a")],
            start_index: 0,
            autoplay: true,
        }).unwrap();

        core.handle_backend_event(BackendEvent::Ended {
            track_id: TrackId::new("a").unwrap(),
        });

        assert_eq!(core.state().status, PlaybackStatus::Paused);
        assert_eq!(core.queue().current().unwrap().id.0, "a");
    }
}
