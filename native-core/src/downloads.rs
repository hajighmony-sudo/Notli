use std::collections::BTreeMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum DownloadStatus { #[default] Queued, Downloading, Paused, Completed, Failed, Cancelled }
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DownloadRequest { pub id: String, pub url: String, pub file_name: Option<String> }
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DownloadSnapshot {
    pub id: String, pub url: String, pub file_name: Option<String>, pub status: DownloadStatus,
    pub received_bytes: u64, pub expected_bytes: Option<u64>, pub error: Option<String>,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DownloadError { InvalidRequest, DuplicateId, NotFound, InvalidTransition, InvalidProgress }

#[derive(Debug, Default)]
pub struct DownloadManager { tasks: BTreeMap<String, DownloadSnapshot> }

impl DownloadManager {
    /// Owns download-task state only. Android performs network and filesystem operations.
    pub fn enqueue(&mut self, request: DownloadRequest) -> Result<(), DownloadError> {
        if request.id.trim().is_empty() || request.id.len() > 128 || !valid_http_url(&request.url)
            || request.file_name.as_ref().is_some_and(|name| !valid_file_name(name)) { return Err(DownloadError::InvalidRequest); }
        if self.tasks.contains_key(&request.id) { return Err(DownloadError::DuplicateId); }
        self.tasks.insert(request.id.clone(), DownloadSnapshot {
            id: request.id, url: request.url, file_name: request.file_name, status: DownloadStatus::Queued,
            received_bytes: 0, expected_bytes: None, error: None,
        });
        Ok(())
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
}
fn valid_http_url(url: &str) -> bool {
    let lower = url.to_ascii_lowercase();
    (lower.starts_with("https://") || lower.starts_with("http://")) && url.len() <= 8192
        && !url.chars().any(char::is_control)
        && url.split_once("://").is_some_and(|(_, rest)| !rest.is_empty() && rest.split('/').next().is_some_and(|host| !host.is_empty()))
}
fn valid_file_name(name: &str) -> bool {
    !name.trim().is_empty() && name.len() <= 255 && !name.chars().any(|c| c.is_control() || matches!(c, '/' | '\\' | ':'))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_non_http_sources() {
        let mut manager = DownloadManager::default();
        assert_eq!(manager.enqueue(DownloadRequest { id: "x".into(), url: "file:///private/file".into(), file_name: None }), Err(DownloadError::InvalidRequest));
    }
    #[test]
    fn download_progress_is_monotonic() {
        let mut manager = DownloadManager::default();
        manager.enqueue(DownloadRequest { id: "x".into(), url: "https://example.com/audio.mp3".into(), file_name: None }).unwrap();
        manager.start("x").unwrap(); manager.update_progress("x", 100, Some(1000)).unwrap();
        assert_eq!(manager.update_progress("x", 50, Some(1000)), Err(DownloadError::InvalidProgress));
    }
}
