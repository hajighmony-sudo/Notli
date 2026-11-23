use crate::model::{RepeatMode, Track, TrackId};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum QueueError {
    InvalidTrack,
    InvalidStartIndex,
    InvalidIndex,
    TrackNotFound,
    InvalidSnapshot,
    /// The queue already holds [`MAX_QUEUE_LEN`] entries.
    QueueFull,
}

/// Upper bound on queue entries. Protects the engine (and the JSON bridge) from unbounded growth.
pub const MAX_QUEUE_LEN: usize = 100_000;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct QueueSnapshot {
    pub tracks: Vec<Track>,
    /// Playback order contains indexes into tracks, including in shuffle mode.
    pub order: Vec<usize>,
    pub cursor: Option<usize>,
    pub shuffle_enabled: bool,
}

#[derive(Debug, Clone, Default)]
pub struct PlaybackQueue {
    tracks: Vec<Track>,
    order: Vec<usize>,
    cursor: Option<usize>,
    shuffle_enabled: bool,
}

impl PlaybackQueue {
    pub fn snapshot(&self) -> QueueSnapshot {
        QueueSnapshot {
            tracks: self.tracks.clone(),
            order: self.order.clone(),
            cursor: self.cursor,
            shuffle_enabled: self.shuffle_enabled,
        }
    }

    pub fn restore(snapshot: QueueSnapshot) -> Result<Self, QueueError> {
        if snapshot.tracks.len() > MAX_QUEUE_LEN {
            return Err(QueueError::InvalidSnapshot);
        }
        validate_tracks(&snapshot.tracks)?;
        if snapshot.order.len() != snapshot.tracks.len() {
            return Err(QueueError::InvalidSnapshot);
        }
        let mut seen = vec![false; snapshot.tracks.len()];
        for index in &snapshot.order {
            let slot = seen.get_mut(*index).ok_or(QueueError::InvalidSnapshot)?;
            if *slot {
                return Err(QueueError::InvalidSnapshot);
            }
            *slot = true;
        }
        match snapshot.cursor {
            Some(cursor) if cursor < snapshot.order.len() => {}
            None if snapshot.order.is_empty() => {}
            _ => return Err(QueueError::InvalidSnapshot),
        }
        Ok(Self {
            tracks: snapshot.tracks,
            order: snapshot.order,
            cursor: snapshot.cursor,
            shuffle_enabled: snapshot.shuffle_enabled,
        })
    }

    pub fn tracks(&self) -> &[Track] {
        &self.tracks
    }

    pub fn len(&self) -> usize {
        self.order.len()
    }

    pub fn is_empty(&self) -> bool {
        self.order.is_empty()
    }

    pub fn shuffle_enabled(&self) -> bool {
        self.shuffle_enabled
    }

    pub fn current(&self) -> Option<&Track> {
        self.cursor
            .and_then(|cursor| self.order.get(cursor))
            .and_then(|index| self.tracks.get(*index))
    }

    pub fn current_index(&self) -> Option<usize> {
        self.cursor
    }

    pub fn resolved_tracks(&self) -> Vec<&Track> {
        self.order.iter().filter_map(|index| self.tracks.get(*index)).collect()
    }

    pub fn replace(&mut self, tracks: Vec<Track>, start_index: usize) -> Result<(), QueueError> {
        if (tracks.is_empty() && start_index != 0)
            || (!tracks.is_empty() && start_index >= tracks.len())
        {
            return Err(QueueError::InvalidStartIndex);
        }
        if tracks.len() > MAX_QUEUE_LEN {
            return Err(QueueError::QueueFull);
        }
        validate_tracks(&tracks)?;
        self.order = (0..tracks.len()).collect();
        self.tracks = tracks;
        self.cursor = if self.tracks.is_empty() { None } else { Some(start_index) };
        self.shuffle_enabled = false;
        Ok(())
    }

    pub fn enqueue(&mut self, track: Track) -> Result<(), QueueError> {
        validate_new_track(&track)?;
        if self.tracks.len() >= MAX_QUEUE_LEN {
            return Err(QueueError::QueueFull);
        }
        let index = self.tracks.len();
        self.tracks.push(track);
        self.order.push(index);
        if self.cursor.is_none() {
            self.cursor = Some(self.order.len() - 1);
        }
        Ok(())
    }

    pub fn enqueue_next(&mut self, track: Track) -> Result<(), QueueError> {
        validate_new_track(&track)?;
        if self.tracks.len() >= MAX_QUEUE_LEN {
            return Err(QueueError::QueueFull);
        }
        let index = self.tracks.len();
        self.tracks.push(track);
        match self.cursor {
            Some(cursor) => self.order.insert(cursor + 1, index),
            None => {
                self.order.push(index);
                self.cursor = Some(0);
            }
        }
        Ok(())
    }

    /// Selects the first matching track ID. Prefer select_index when duplicate tracks are queued.
    pub fn select(&mut self, id: &TrackId) -> Result<&Track, QueueError> {
        let raw = self
            .tracks
            .iter()
            .position(|track| &track.id == id)
            .ok_or(QueueError::TrackNotFound)?;
        self.cursor = self.order.iter().position(|index| *index == raw);
        self.current().ok_or(QueueError::TrackNotFound)
    }

    pub fn select_index(&mut self, queue_index: usize) -> Result<&Track, QueueError> {
        if queue_index >= self.order.len() {
            return Err(QueueError::InvalidIndex);
        }
        self.cursor = Some(queue_index);
        self.current().ok_or(QueueError::InvalidIndex)
    }

    pub fn next(&mut self, repeat: RepeatMode) -> Option<&Track> {
        let cursor = self.cursor?;
        if repeat == RepeatMode::One {
            return self.current();
        }
        if cursor + 1 < self.order.len() {
            self.cursor = Some(cursor + 1);
        } else if repeat == RepeatMode::All && !self.order.is_empty() {
            self.cursor = Some(0);
        } else {
            return None;
        }
        self.current()
    }

    pub fn previous(&mut self, repeat: RepeatMode) -> Option<&Track> {
        let cursor = self.cursor?;
        if cursor > 0 {
            self.cursor = Some(cursor - 1);
        } else if repeat == RepeatMode::All && !self.order.is_empty() {
            self.cursor = Some(self.order.len() - 1);
        } else {
            return None;
        }
        self.current()
    }

    /// Removes an item by its visible playback-queue index, not by track identity.
    pub fn remove_at(&mut self, queue_index: usize) -> Result<bool, QueueError> {
        let raw_index = *self.order.get(queue_index).ok_or(QueueError::InvalidIndex)?;
        let was_current = self.cursor == Some(queue_index);
        let selected_raw = self.cursor.and_then(|cursor| self.order.get(cursor)).copied();

        self.tracks.remove(raw_index);
        self.order.remove(queue_index);
        for index in &mut self.order {
            if *index > raw_index {
                *index -= 1;
            }
        }

        if self.order.is_empty() {
            self.cursor = None;
        } else if was_current {
            self.cursor = Some(queue_index.min(self.order.len() - 1));
        } else {
            let selected_raw = selected_raw.map(|index| {
                if index > raw_index { index - 1 } else { index }
            });
            self.cursor = selected_raw.and_then(|selected| {
                self.order.iter().position(|index| *index == selected)
            });
            if self.cursor.is_none() {
                self.cursor = Some(0);
            }
        }
        Ok(was_current)
    }

    pub fn remove(&mut self, id: &TrackId) -> Result<bool, QueueError> {
        let raw_index = self
            .tracks
            .iter()
            .position(|track| &track.id == id)
            .ok_or(QueueError::TrackNotFound)?;
        let queue_index = self
            .order
            .iter()
            .position(|index| *index == raw_index)
            .ok_or(QueueError::TrackNotFound)?;
        self.remove_at(queue_index)
    }

    /// Reorders visible queue entries while preserving the currently selected entry.
    pub fn move_at(&mut self, from: usize, to: usize) -> Result<(), QueueError> {
        if from >= self.order.len() || to >= self.order.len() {
            return Err(QueueError::InvalidIndex);
        }
        if from == to {
            return Ok(());
        }
        let selected_raw = self.cursor.and_then(|cursor| self.order.get(cursor)).copied();
        let moved = self.order.remove(from);
        self.order.insert(to, moved);
        self.cursor = selected_raw.and_then(|selected| {
            self.order.iter().position(|index| *index == selected)
        });
        Ok(())
    }

    pub fn move_track(&mut self, id: &TrackId, new_index: usize) -> Result<(), QueueError> {
        let raw_index = self
            .tracks
            .iter()
            .position(|track| &track.id == id)
            .ok_or(QueueError::TrackNotFound)?;
        let from = self
            .order
            .iter()
            .position(|index| *index == raw_index)
            .ok_or(QueueError::TrackNotFound)?;
        self.move_at(from, new_index)
    }

    pub fn set_shuffle(&mut self, enabled: bool, seed: u64) {
        if enabled == self.shuffle_enabled {
            return;
        }
        let current = self.cursor.and_then(|cursor| self.order.get(cursor)).copied();
        self.order = (0..self.tracks.len()).collect();
        if enabled {
            let mut state = if seed == 0 { 0x9E3779B97F4A7C15 } else { seed };
            for i in (1..self.order.len()).rev() {
                state ^= state << 13;
                state ^= state >> 7;
                state ^= state << 17;
                self.order.swap(i, (state as usize) % (i + 1));
            }
            // Like the existing Android player, a freshly shuffled queue starts with the
            // current track, so "previous" never jumps to an arbitrary random entry.
            if let Some(selected) = current {
                if let Some(position) = self.order.iter().position(|index| *index == selected) {
                    self.order.swap(0, position);
                }
            }
        }
        self.shuffle_enabled = enabled;
        self.cursor = current.and_then(|selected| {
            self.order.iter().position(|index| *index == selected)
        });
        if self.cursor.is_none() && !self.order.is_empty() {
            self.cursor = Some(0);
        }
    }
}

fn validate_tracks(tracks: &[Track]) -> Result<(), QueueError> {
    for track in tracks {
        if !track.is_valid() {
            return Err(QueueError::InvalidTrack);
        }
    }
    Ok(())
}

fn validate_new_track(track: &Track) -> Result<(), QueueError> {
    if !track.is_valid() {
        return Err(QueueError::InvalidTrack);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn track(id: &str, title: &str) -> Track {
        Track {
            id: TrackId::new(id).unwrap(),
            title: title.to_owned(),
            artist: None,
            album: None,
            source_uri: format!("content://music/{title}"),
            duration_ms: Some(60_000),
        }
    }

    #[test]
    fn allows_same_song_more_than_once_in_queue() {
        let mut queue = PlaybackQueue::default();
        queue.replace(vec![track("same", "first"), track("same", "second")], 0).unwrap();
        assert_eq!(queue.len(), 2);
        assert_eq!(queue.resolved_tracks()[1].title, "second");
    }

    #[test]
    fn index_operations_target_duplicate_queue_entries_exactly() {
        let mut queue = PlaybackQueue::default();
        queue.replace(vec![track("same", "first"), track("same", "second")], 0).unwrap();
        queue.select_index(1).unwrap();
        assert!(!queue.remove_at(0).unwrap());
        assert_eq!(queue.current().unwrap().title, "second");
    }

    fn numbered(count: usize) -> Vec<Track> {
        (0..count).map(|i| track(&format!("t{i}"), &format!("t{i}"))).collect()
    }

    #[test]
    fn enabling_shuffle_moves_current_track_first_and_is_a_permutation() {
        let mut queue = PlaybackQueue::default();
        queue.replace(numbered(10), 4).unwrap();
        queue.set_shuffle(true, 7);

        assert!(queue.shuffle_enabled());
        assert_eq!(queue.current().unwrap().id.0, "t4");
        assert_eq!(queue.current_index(), Some(0));

        let mut ids: Vec<String> = queue.resolved_tracks().iter().map(|t| t.id.0.clone()).collect();
        ids.sort();
        let mut expected: Vec<String> = (0..10).map(|i| format!("t{i}")).collect();
        expected.sort();
        assert_eq!(ids, expected);
    }

    #[test]
    fn disabling_shuffle_restores_library_order_around_current_track() {
        let mut queue = PlaybackQueue::default();
        queue.replace(numbered(6), 2).unwrap();
        queue.set_shuffle(true, 99);
        queue.next(RepeatMode::Off);
        let playing = queue.current().unwrap().id.0.clone();
        queue.set_shuffle(false, 0);

        assert_eq!(queue.current().unwrap().id.0, playing);
        let ids: Vec<&str> = queue.resolved_tracks().iter().map(|t| t.id.0.as_str()).collect();
        assert_eq!(ids, ["t0", "t1", "t2", "t3", "t4", "t5"]);
    }

    #[test]
    fn shuffle_is_deterministic_for_a_seed() {
        let order = |seed| {
            let mut queue = PlaybackQueue::default();
            queue.replace(numbered(20), 0).unwrap();
            queue.set_shuffle(true, seed);
            queue.snapshot().order
        };
        assert_eq!(order(5), order(5));
        assert_ne!(order(5), order(6));
    }

    #[test]
    fn queue_rejects_growth_past_the_limit() {
        let mut queue = PlaybackQueue::default();
        assert_eq!(queue.replace(numbered(MAX_QUEUE_LEN + 1), 0), Err(QueueError::QueueFull));
        assert!(queue.is_empty());

        queue.replace(numbered(MAX_QUEUE_LEN), 0).unwrap();
        assert_eq!(queue.enqueue(track("extra", "extra")), Err(QueueError::QueueFull));
        assert_eq!(queue.enqueue_next(track("extra", "extra")), Err(QueueError::QueueFull));
        assert_eq!(queue.len(), MAX_QUEUE_LEN);
    }

    #[test]
    fn restore_round_trips_a_shuffled_queue() {
        let mut queue = PlaybackQueue::default();
        queue.replace(numbered(8), 3).unwrap();
        queue.set_shuffle(true, 1234);
        let snapshot = queue.snapshot();
        let restored = PlaybackQueue::restore(snapshot.clone()).unwrap();
        assert_eq!(restored.snapshot(), snapshot);
    }

    #[test]
    fn moving_queue_entry_preserves_current_entry() {
        let mut queue = PlaybackQueue::default();
        queue.replace(vec![track("a", "a"), track("b", "b"), track("c", "c")], 1).unwrap();
        queue.move_at(1, 2).unwrap();
        assert_eq!(queue.current().unwrap().id.0, "b");
        assert_eq!(queue.current_index(), Some(2));
    }
}
