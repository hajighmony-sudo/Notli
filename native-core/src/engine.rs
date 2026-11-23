use serde::{Deserialize, Serialize};
use crate::model::{BackendAction, BackendEvent, PlaybackState, PlaybackStatus, PlayerCommand, PlayerEvent, Track, TrackId};
use crate::queue::{PlaybackQueue, QueueError, QueueSnapshot};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CoreError { Queue(QueueError), EmptyQueue, NoCurrentTrack }
impl From<QueueError> for CoreError { fn from(value: QueueError) -> Self { Self::Queue(value) } }

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct CoreOutput { pub actions: Vec<BackendAction>, pub events: Vec<PlayerEvent> }

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PlayerSnapshot { pub state: PlaybackState, pub queue: QueueSnapshot }

#[derive(Debug, Default)]
pub struct PlayerCore { state: PlaybackState, queue: PlaybackQueue }

impl PlayerCore {
    pub fn new() -> Self { Self::default() }
    pub fn state(&self) -> &PlaybackState { &self.state }
    pub fn queue(&self) -> &PlaybackQueue { &self.queue }
    pub fn snapshot(&self) -> PlayerSnapshot { PlayerSnapshot { state: self.state.clone(), queue: self.queue.snapshot() } }

    pub fn restore(snapshot: PlayerSnapshot) -> Result<Self, CoreError> {
        let queue = PlaybackQueue::restore(snapshot.queue)?;
        if let Some(id) = &snapshot.state.current_track {
            if !queue.tracks().iter().any(|track| &track.id == id) { return Err(CoreError::NoCurrentTrack); }
        } else if !queue.is_empty() { return Err(CoreError::NoCurrentTrack); }
        Ok(Self { state: snapshot.state, queue })
    }

    pub fn handle(&mut self, command: PlayerCommand) -> Result<CoreOutput, CoreError> {
        let mut out = CoreOutput::default();
        match command {
            PlayerCommand::SetQueue { tracks, start_index, autoplay } => {
                self.queue.replace(tracks, start_index)?;
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
                let track = self.queue.next(self.state.repeat_mode).cloned().ok_or(CoreError::EmptyQueue)?;
                self.select_track(track, true, &mut out);
            }
            PlayerCommand::Previous => {
                let track = if self.state.position_ms > 3000 {
                    self.state.position_ms = 0;
                    out.actions.push(BackendAction::SeekTo(0));
                    None
                } else { self.queue.previous(self.state.repeat_mode).cloned() };
                if let Some(track) = track { self.select_track(track, true, &mut out); }
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
        self.state.revision = self.state.revision.wrapping_add(1);
        out.events.push(PlayerEvent::StateChanged(self.state.clone()));
        Ok(out)
    }

    pub fn handle_backend_event(&mut self, event: BackendEvent) -> CoreOutput {
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
                if let Some(track) = self.queue.next(self.state.repeat_mode).cloned() { self.select_track(track, true, &mut out); }
                else { self.state.status = PlaybackStatus::Completed; self.state.position_ms = self.state.duration_ms.unwrap_or(self.state.position_ms); out.events.push(PlayerEvent::PlaybackCompleted); }
            }
            BackendEvent::Failed { track_id, message } if self.state.current_track.as_ref() == Some(&track_id) => {
                self.state.status = PlaybackStatus::Error;
                self.state.last_error = Some(message.clone());
                out.events.push(PlayerEvent::PlaybackError(message));
            }
            _ => {}
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
    fn track(id: &str) -> Track {
        Track { id: TrackId::new(id).unwrap(), title: format!("Track {id}"), artist: None, album: None, source_uri: format!("content://music/{id}"), duration_ms: Some(60_000) }
    }
    #[test]
    fn invalid_queue_does_not_destroy_current_queue() {
        let mut core = PlayerCore::new();
        core.handle(PlayerCommand::SetQueue { tracks: vec![track("a")], start_index: 0, autoplay: false }).unwrap();
        assert!(core.handle(PlayerCommand::SetQueue { tracks: vec![track("x")], start_index: 4, autoplay: true }).is_err());
        assert_eq!(core.queue().current().unwrap().id.0, "a");
    }
    #[test]
    fn seek_is_clamped_to_known_duration() {
        let mut core = PlayerCore::new();
        core.handle(PlayerCommand::SetQueue { tracks: vec![track("a")], start_index: 0, autoplay: true }).unwrap();
        core.handle(PlayerCommand::Seek { position_ms: 90_000 }).unwrap();
        assert_eq!(core.state().position_ms, 60_000);
    }
}
