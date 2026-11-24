use std::collections::BTreeMap;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
pub enum DownloadStatus { #[default] Queued, Downloading, Paused, Completed, Failed, Cancelled }
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DownloadRequest { pub id: String, pub url: String, pub file_name: Option<String> }
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DownloadSnapshot {
    pub id: String, pub url: String, pub file_name: Option<String>, pub status: DownloadStatus,
    pub received_bytes: u64, pub expected_bytes: Option<u64>, pub error: Option<String>,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DownloadError { InvalidRequest, DuplicateId, NotFound, InvalidTransition, InvalidProgress, TooManyTasks }

/// Maximum number of tracked download tasks. Finished tasks can be removed to make room.
pub const MAX_DOWNLOAD_TASKS: usize = 1_000;

#[derive(Debug, Default)]
pub struct DownloadManager { tasks: BTreeMap<String, DownloadSnapshot> }

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum DownloadCommand {
    Enqueue { request: DownloadRequest },
    Start { id: String },
    Pause { id: String },
    Progress { id: String, received_bytes: u64, expected_bytes: Option<u64> },
    Complete { id: String },
    Fail { id: String, message: String },
    Cancel { id: String },
    Retry { id: String },
    /// Forgets a finished (completed, failed or cancelled) task. Returns its final snapshot.
    Remove { id: String },
}

impl DownloadManager {
    /// Owns download-task state only. Android performs network and filesystem operations.
    pub fn enqueue(&mut self, request: DownloadRequest) -> Result<(), DownloadError> {
        if request.id.trim().is_empty() || request.id.len() > 128 || !valid_http_url(&request.url)
            || request.file_name.as_ref().is_some_and(|name| !valid_file_name(name)) { return Err(DownloadError::InvalidRequest); }
        if self.tasks.contains_key(&request.id) { return Err(DownloadError::DuplicateId); }
        if self.tasks.len() >= MAX_DOWNLOAD_TASKS { return Err(DownloadError::TooManyTasks); }
        self.tasks.insert(request.id.clone(), DownloadSnapshot {
            id: request.id, url: request.url, file_name: request.file_name, status: DownloadStatus::Queued,
            received_bytes: 0, expected_bytes: None, error: None,
        });
        Ok(())
    }
    pub fn handle(&mut self, command: DownloadCommand) -> Result<DownloadSnapshot, DownloadError> {
        let id = match &command {
            DownloadCommand::Enqueue { request } => request.id.clone(),
            DownloadCommand::Start { id } | DownloadCommand::Pause { id }
            | DownloadCommand::Progress { id, .. } | DownloadCommand::Complete { id }
            | DownloadCommand::Fail { id, .. } | DownloadCommand::Cancel { id }
            | DownloadCommand::Retry { id } | DownloadCommand::Remove { id } => id.clone(),
        };
        if let DownloadCommand::Remove { id } = &command {
            // The task disappears, so report the snapshot it had when it was removed.
            return self.remove(id);
        }
        match command {
            DownloadCommand::Enqueue { request } => self.enqueue(request)?,
            DownloadCommand::Start { id } => self.start(&id)?,
            DownloadCommand::Pause { id } => self.pause(&id)?,
            DownloadCommand::Progress { id, received_bytes, expected_bytes } => {
                self.update_progress(&id, received_bytes, expected_bytes)?
            }
            DownloadCommand::Complete { id } => self.complete(&id)?,
            DownloadCommand::Fail { id, message } => self.fail(&id, message)?,
            DownloadCommand::Cancel { id } => self.cancel(&id)?,
            DownloadCommand::Retry { id } => self.retry(&id)?,
            DownloadCommand::Remove { .. } => unreachable!("remove is handled above"),
        }
        self.get(&id).cloned().ok_or(DownloadError::NotFound)
    }

    pub fn get(&self, id: &str) -> Option<&DownloadSnapshot> { self.tasks.get(id) }
    pub fn all(&self) -> Vec<DownloadSnapshot> { self.tasks.values().cloned().collect() }
    pub fn start(&mut self, id: &str) -> Result<(), DownloadError> {
        let task = self.tasks.get_mut(id).ok_or(DownloadError::NotFound)?;
        if !matches!(task.status, DownloadStatus::Queued | DownloadStatus::Paused) { return Err(DownloadError::InvalidTransition); }
        task.status = DownloadStatus::Downloading; task.error = None; Ok(())
    }
    pub fn pause(&mut self, id: &str) -> Result<(), DownloadError> {
        let task = self.tasks.get_mut(id).ok_or(DownloadError::NotFound)?;
        if task.status != DownloadStatus::Downloading { return Err(DownloadError::InvalidTransition); }
        task.status = DownloadStatus::Paused; Ok(())
    }
    pub fn update_progress(&mut self, id: &str, received: u64, total: Option<u64>) -> Result<(), DownloadError> {
        let task = self.tasks.get_mut(id).ok_or(DownloadError::NotFound)?;
        if task.status != DownloadStatus::Downloading || received < task.received_bytes || total.is_some_and(|size| received > size) { return Err(DownloadError::InvalidProgress); }
        task.received_bytes = received; task.expected_bytes = total; Ok(())
    }
    pub fn complete(&mut self, id: &str) -> Result<(), DownloadError> {
        let task = self.tasks.get_mut(id).ok_or(DownloadError::NotFound)?;
        if task.status != DownloadStatus::Downloading || task.expected_bytes.is_some_and(|size| size != task.received_bytes) { return Err(DownloadError::InvalidTransition); }
        task.status = DownloadStatus::Completed; Ok(())
    }
    pub fn fail(&mut self, id: &str, message: impl Into<String>) -> Result<(), DownloadError> {
        let task = self.tasks.get_mut(id).ok_or(DownloadError::NotFound)?;
        if !matches!(task.status, DownloadStatus::Queued | DownloadStatus::Downloading | DownloadStatus::Paused) { return Err(DownloadError::InvalidTransition); }
        task.status = DownloadStatus::Failed; task.error = Some(message.into().chars().take(512).collect()); Ok(())
    }

    pub fn cancel(&mut self, id: &str) -> Result<(), DownloadError> {
        let task = self.tasks.get_mut(id).ok_or(DownloadError::NotFound)?;
        if !matches!(task.status, DownloadStatus::Queued | DownloadStatus::Downloading | DownloadStatus::Paused) {
            return Err(DownloadError::InvalidTransition);
        }
        task.status = DownloadStatus::Cancelled;
        Ok(())
    }

    /// Removes a finished task and returns its final snapshot. Active tasks must be cancelled first.
    pub fn remove(&mut self, id: &str) -> Result<DownloadSnapshot, DownloadError> {
        let task = self.tasks.get(id).ok_or(DownloadError::NotFound)?;
        if !is_finished(task.status) {
            return Err(DownloadError::InvalidTransition);
        }
        self.tasks.remove(id).ok_or(DownloadError::NotFound)
    }

    /// Drops every finished task and returns how many were removed.
    pub fn clear_finished(&mut self) -> usize {
        let before = self.tasks.len();
        self.tasks.retain(|_, task| !is_finished(task.status));
        before - self.tasks.len()
    }

    pub fn retry(&mut self, id: &str) -> Result<(), DownloadError> {
        let task = self.tasks.get_mut(id).ok_or(DownloadError::NotFound)?;
        if !matches!(task.status, DownloadStatus::Failed | DownloadStatus::Cancelled) {
            return Err(DownloadError::InvalidTransition);
        }
        task.status = DownloadStatus::Queued;
        task.received_bytes = 0;
        task.expected_bytes = None;
        task.error = None;
        Ok(())
    }
}
fn is_finished(status: DownloadStatus) -> bool {
    matches!(status, DownloadStatus::Completed | DownloadStatus::Failed | DownloadStatus::Cancelled)
}

fn valid_http_url(url: &str) -> bool {
    let lower = url.to_ascii_lowercase();
    (lower.starts_with("https://") || lower.starts_with("http://")) && url.len() <= 8192
        && !url.chars().any(char::is_control)
        && url.split_once("://").is_some_and(|(_, rest)| !rest.is_empty() && rest.split('/').next().is_some_and(|host| !host.is_empty()))
}
fn valid_file_name(name: &str) -> bool {
    let trimmed = name.trim();
    !trimmed.is_empty()
        && trimmed != "."
        && trimmed != ".."
        && name.len() <= 255
        && !name.chars().any(|c| c.is_control() || matches!(c, '/' | '\\' | ':'))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_path_traversal_file_names() {
        let mut manager = DownloadManager::default();
        for file_name in [".", "..", "../outside.mp3", "folder\\\\song.mp3"] {
            assert_eq!(
                manager.enqueue(DownloadRequest {
                    id: format!("id-{file_name}"),
                    url: "https://example.com/audio.mp3".into(),
                    file_name: Some(file_name.into()),
                }),
                Err(DownloadError::InvalidRequest)
            );
        }
    }

    #[test]
    fn rejects_non_http_sources() {
        let mut manager = DownloadManager::default();
        assert_eq!(manager.enqueue(DownloadRequest { id: "x".into(), url: "file:///private/file".into(), file_name: None }), Err(DownloadError::InvalidRequest));
    }
    #[test]
    fn download_can_be_cancelled_and_retried_without_stale_progress() {
        let mut manager = DownloadManager::default();
        manager.enqueue(DownloadRequest {
            id: "retry".into(),
            url: "https://example.com/audio.mp3".into(),
            file_name: None,
        }).unwrap();
        manager.start("retry").unwrap();
        manager.update_progress("retry", 128, Some(1024)).unwrap();
        manager.cancel("retry").unwrap();
        manager.retry("retry").unwrap();

        let task = manager.get("retry").unwrap();
        assert_eq!(task.status, DownloadStatus::Queued);
        assert_eq!(task.received_bytes, 0);
        assert_eq!(task.expected_bytes, None);
        assert_eq!(task.error, None);
    }

    fn request(id: &str) -> DownloadRequest {
        DownloadRequest { id: id.into(), url: "https://example.com/audio.mp3".into(), file_name: None }
    }

    #[test]
    fn only_finished_tasks_can_be_removed() {
        let mut manager = DownloadManager::default();
        manager.enqueue(request("a")).unwrap();
        assert_eq!(manager.remove("a"), Err(DownloadError::InvalidTransition));
        manager.start("a").unwrap();
        assert_eq!(manager.remove("a"), Err(DownloadError::InvalidTransition));
        manager.cancel("a").unwrap();

        let removed = manager.remove("a").unwrap();
        assert_eq!(removed.status, DownloadStatus::Cancelled);
        assert!(manager.get("a").is_none());
        assert_eq!(manager.remove("a"), Err(DownloadError::NotFound));
    }

    #[test]
    fn remove_command_reports_the_final_snapshot() {
        let mut manager = DownloadManager::default();
        manager.handle(DownloadCommand::Enqueue { request: request("a") }).unwrap();
        manager.handle(DownloadCommand::Start { id: "a".into() }).unwrap();
        manager.handle(DownloadCommand::Progress { id: "a".into(), received_bytes: 10, expected_bytes: Some(10) }).unwrap();
        manager.handle(DownloadCommand::Complete { id: "a".into() }).unwrap();

        let removed = manager.handle(DownloadCommand::Remove { id: "a".into() }).unwrap();
        assert_eq!(removed.status, DownloadStatus::Completed);
        assert_eq!(removed.received_bytes, 10);
        assert!(manager.all().is_empty());
    }

    #[test]
    fn clear_finished_keeps_active_tasks() {
        let mut manager = DownloadManager::default();
        for id in ["queued", "running", "failed", "cancelled"] {
            manager.enqueue(request(id)).unwrap();
        }
        manager.start("running").unwrap();
        manager.start("failed").unwrap();
        manager.fail("failed", "network").unwrap();
        manager.cancel("cancelled").unwrap();

        assert_eq!(manager.clear_finished(), 2);
        let remaining: Vec<String> = manager.all().into_iter().map(|t| t.id).collect();
        assert_eq!(remaining, ["queued", "running"]);
    }

    #[test]
    fn task_count_is_capped_until_finished_tasks_are_removed() {
        let mut manager = DownloadManager::default();
        for i in 0..MAX_DOWNLOAD_TASKS {
            manager.enqueue(request(&format!("d{i}"))).unwrap();
        }
        assert_eq!(manager.enqueue(request("overflow")), Err(DownloadError::TooManyTasks));

        manager.cancel("d0").unwrap();
        assert_eq!(manager.clear_finished(), 1);
        manager.enqueue(request("overflow")).unwrap();
    }

    #[test]
    fn completion_requires_all_expected_bytes() {
        let mut manager = DownloadManager::default();
        manager.enqueue(request("a")).unwrap();
        manager.start("a").unwrap();
        manager.update_progress("a", 50, Some(100)).unwrap();
        assert_eq!(manager.complete("a"), Err(DownloadError::InvalidTransition));
        manager.update_progress("a", 100, Some(100)).unwrap();
        manager.complete("a").unwrap();
        assert_eq!(manager.start("a"), Err(DownloadError::InvalidTransition));
    }

    #[test]
    fn paused_download_resumes_with_its_progress() {
        let mut manager = DownloadManager::default();
        manager.enqueue(request("a")).unwrap();
        manager.start("a").unwrap();
        manager.update_progress("a", 40, Some(100)).unwrap();
        manager.pause("a").unwrap();
        manager.start("a").unwrap();
        assert_eq!(manager.get("a").unwrap().received_bytes, 40);
        assert_eq!(manager.update_progress("a", 30, Some(100)), Err(DownloadError::InvalidProgress));
    }

    #[test]
    fn download_progress_is_monotonic() {
        let mut manager = DownloadManager::default();
        manager.enqueue(DownloadRequest { id: "x".into(), url: "https://example.com/audio.mp3".into(), file_name: None }).unwrap();
        manager.start("x").unwrap(); manager.update_progress("x", 100, Some(1000)).unwrap();
        assert_eq!(manager.update_progress("x", 50, Some(1000)), Err(DownloadError::InvalidProgress));
    }
}
