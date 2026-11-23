use crate::model::{RepeatMode, Track, TrackId};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum QueueError { InvalidTrack, DuplicateTrack, InvalidStartIndex, InvalidIndex, TrackNotFound, InvalidSnapshot }

#[derive(Debug, Clone, PartialEq, Eq)]
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
        QueueSnapshot { tracks: self.tracks.clone(), order: self.order.clone(), cursor: self.cursor, shuffle_enabled: self.shuffle_enabled }
    }

    pub fn restore(snapshot: QueueSnapshot) -> Result<Self, QueueError> {
        validate_tracks(&snapshot.tracks)?;
        if snapshot.order.len() != snapshot.tracks.len() { return Err(QueueError::InvalidSnapshot); }
        let mut seen = vec![false; snapshot.tracks.len()];
        for index in &snapshot.order {
            let slot = seen.get_mut(*index).ok_or(QueueError::InvalidSnapshot)?;
            if *slot { return Err(QueueError::InvalidSnapshot); }
            *slot = true;
        }
        match snapshot.cursor {
            Some(cursor) if cursor < snapshot.order.len() => {}
            None if snapshot.order.is_empty() => {}
            _ => return Err(QueueError::InvalidSnapshot),
        }
        Ok(Self { tracks: snapshot.tracks, order: snapshot.order, cursor: snapshot.cursor, shuffle_enabled: snapshot.shuffle_enabled })
    }

    pub fn tracks(&self) -> &[Track] { &self.tracks }
    pub fn len(&self) -> usize { self.tracks.len() }
    pub fn is_empty(&self) -> bool { self.tracks.is_empty() }
    pub fn shuffle_enabled(&self) -> bool { self.shuffle_enabled }
    pub fn current(&self) -> Option<&Track> {
        self.cursor.and_then(|c| self.order.get(c)).and_then(|i| self.tracks.get(*i))
    }
    pub fn current_index(&self) -> Option<usize> { self.cursor }
    pub fn resolved_tracks(&self) -> Vec<&Track> { self.order.iter().filter_map(|i| self.tracks.get(*i)).collect() }

    pub fn replace(&mut self, tracks: Vec<Track>, start_index: usize) -> Result<(), QueueError> {
        if (tracks.is_empty() && start_index != 0) || (!tracks.is_empty() && start_index >= tracks.len()) { return Err(QueueError::InvalidStartIndex); }
        validate_tracks(&tracks)?;
        self.order = (0..tracks.len()).collect();
        self.tracks = tracks;
        self.cursor = if self.tracks.is_empty() { None } else { Some(start_index) };
        self.shuffle_enabled = false;
        Ok(())
    }

    pub fn enqueue(&mut self, track: Track) -> Result<(), QueueError> {
        validate_new_track(&self.tracks, &track)?;
        let index = self.tracks.len();
        self.tracks.push(track);
        self.order.push(index);
        if self.cursor.is_none() { self.cursor = Some(self.order.len() - 1); }
        Ok(())
    }

    pub fn enqueue_next(&mut self, track: Track) -> Result<(), QueueError> {
        validate_new_track(&self.tracks, &track)?;
        let index = self.tracks.len();
        self.tracks.push(track);
        match self.cursor {
            Some(cursor) => self.order.insert(cursor + 1, index),
            None => { self.order.push(index); self.cursor = Some(0); }
        }
        Ok(())
    }

    pub fn select(&mut self, id: &TrackId) -> Result<&Track, QueueError> {
        let raw = self.tracks.iter().position(|t| &t.id == id).ok_or(QueueError::TrackNotFound)?;
        self.cursor = self.order.iter().position(|i| *i == raw);
        self.current().ok_or(QueueError::TrackNotFound)
    }

    pub fn next(&mut self, repeat: RepeatMode) -> Option<&Track> {
        let cursor = self.cursor?;
        if repeat == RepeatMode::One { return self.current(); }
        if cursor + 1 < self.order.len() { self.cursor = Some(cursor + 1); }
        else if !self.order.is_empty() { self.cursor = Some(0); }
        else { return None; }
        self.current()
    }

    pub fn previous(&mut self, repeat: RepeatMode) -> Option<&Track> {
        let cursor = self.cursor?;
        if cursor > 0 { self.cursor = Some(cursor - 1); }
        else if repeat == RepeatMode::All && !self.order.is_empty() { self.cursor = Some(self.order.len() - 1); }
        else { return None; }
        self.current()
    }

    /// Returns true if the removed track was selected.
    pub fn remove(&mut self, id: &TrackId) -> Result<bool, QueueError> {
        let raw = self.tracks.iter().position(|t| &t.id == id).ok_or(QueueError::TrackNotFound)?;
        let selected_raw = self.current().map(|t| t.id.clone());
        let removed_selected = selected_raw.as_ref() == Some(id);
        self.tracks.remove(raw);
        self.order.retain(|index| *index != raw);
        for index in &mut self.order { if *index > raw { *index -= 1; } }
        if self.order.is_empty() { self.cursor = None; }
        else {
            self.cursor = selected_raw.and_then(|selected| self.order.iter().position(|i| self.tracks[*i].id == selected));
            if self.cursor.is_none() { self.cursor = Some(0); }
        }
        Ok(removed_selected)
    }

    pub fn move_track(&mut self, id: &TrackId, new_index: usize) -> Result<(), QueueError> {
        if new_index >= self.tracks.len() { return Err(QueueError::InvalidIndex); }
        let from = self.tracks.iter().position(|t| &t.id == id).ok_or(QueueError::TrackNotFound)?;
        if from == new_index { return Ok(()); }
        let selected = self.current().map(|t| t.id.clone());
        let track = self.tracks.remove(from);
        self.tracks.insert(new_index, track);
        let remap = |i: usize| if i == from { new_index } else if from < new_index && i > from && i <= new_index { i - 1 } else if from > new_index && i >= new_index && i < from { i + 1 } else { i };
        for index in &mut self.order { *index = remap(*index); }
        if !self.shuffle_enabled { self.order.sort_unstable(); }
        self.cursor = selected.and_then(|id| self.order.iter().position(|i| self.tracks[*i].id == id));
        Ok(())
    }

    pub fn set_shuffle(&mut self, enabled: bool, seed: u64) {
        if enabled == self.shuffle_enabled { return; }
        let current = self.current().map(|t| t.id.clone());
        self.order = (0..self.tracks.len()).collect();
        if enabled {
            let mut state = if seed == 0 { 0x9E3779B97F4A7C15 } else { seed };
            for i in (1..self.order.len()).rev() {
                state ^= state << 13; state ^= state >> 7; state ^= state << 17;
                self.order.swap(i, (state as usize) % (i + 1));
            }
        }
        self.shuffle_enabled = enabled;
        self.cursor = current.and_then(|id| self.order.iter().position(|i| self.tracks[*i].id == id));
        if self.cursor.is_none() && !self.order.is_empty() { self.cursor = Some(0); }
    }
}

fn validate_tracks(tracks: &[Track]) -> Result<(), QueueError> {
    for (i, track) in tracks.iter().enumerate() {
        if !track.is_valid() { return Err(QueueError::InvalidTrack); }
        if tracks[..i].iter().any(|prior| prior.id == track.id) { return Err(QueueError::DuplicateTrack); }
    }
    Ok(())
}
fn validate_new_track(tracks: &[Track], track: &Track) -> Result<(), QueueError> {
    if !track.is_valid() { return Err(QueueError::InvalidTrack); }
    if tracks.iter().any(|prior| prior.id == track.id) { return Err(QueueError::DuplicateTrack); }
    Ok(())
}
