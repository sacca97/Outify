//! Keeps the audio cache to a small window around the current track.
//!
//! librespot caches every track it downloads completely and never removes
//! them. The previous, current and next track are enough to ride out a short
//! outage, so everything older is dropped after each track change.

use std::{
    fs,
    path::{Path, PathBuf},
    thread,
    time::SystemTime,
};

use librespot_metadata::audio::AudioItem;
use log::{debug, warn};

/// Tracks kept after a track change: previous, current and next.
const KEEP_FILES: usize = 3;

/// A cached file lives at `<2 hex characters>/<38 hex characters>`.
const FOLDER_NAME_LEN: usize = 2;
const FILE_NAME_LEN: usize = 38;

/// Marks the files of the track that just started as recently used and drops
/// the older ones.
pub fn track_started(audio_item: &AudioItem) {
    let Some(dir) = crate::CACHE_DIR.get() else {
        return;
    };
    let paths: Vec<PathBuf> = audio_item
        .files
        .0
        .values()
        .map(|file_id| {
            let name = file_id.to_base16();
            dir.join(&name[..FOLDER_NAME_LEN]).join(&name[FOLDER_NAME_LEN..])
        })
        .collect();
    let dir = dir.clone();

    // Cleaning is not urgent, so keep it off the player event thread.
    thread::spawn(move || {
        for path in &paths {
            touch(path);
        }
        prune(&dir, KEEP_FILES);
    });
}

/// Makes `path` the most recently used file. Does nothing if it is not cached.
fn touch(path: &Path) {
    if let Ok(file) = fs::OpenOptions::new().write(true).open(path) {
        let _ = file.set_modified(SystemTime::now());
    }
}

/// Removes all but the `keep` most recently used cached files in `dir`.
fn prune(dir: &Path, keep: usize) {
    let mut files = cached_files(dir);
    if files.len() <= keep {
        return;
    }

    files.sort_by(|a, b| b.1.cmp(&a.1));
    for (path, _) in files.into_iter().skip(keep) {
        match fs::remove_file(&path) {
            Ok(()) => debug!("dropped cached audio {}", path.display()),
            Err(e) => warn!("cannot drop cached audio {}: {e}", path.display()),
        }
    }
}

/// Audio files in `dir` with their last use. Anything else in the directory is
/// ignored, it is shared with other caches.
fn cached_files(dir: &Path) -> Vec<(PathBuf, SystemTime)> {
    let Ok(folders) = fs::read_dir(dir) else {
        return Vec::new();
    };

    folders
        .flatten()
        .filter(|folder| is_hex(&folder.file_name().to_string_lossy(), FOLDER_NAME_LEN))
        .filter_map(|folder| fs::read_dir(folder.path()).ok())
        .flat_map(|files| files.flatten())
        .filter(|file| is_hex(&file.file_name().to_string_lossy(), FILE_NAME_LEN))
        .filter_map(|file| {
            let modified = file.metadata().ok()?.modified().ok()?;
            Some((file.path(), modified))
        })
        .collect()
}

fn is_hex(name: &str, len: usize) -> bool {
    name.len() == len && name.chars().all(|c| c.is_ascii_hexdigit())
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::*;

    fn scratch_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("outify-audio-cache-{name}"));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn cache_file(dir: &Path, folder: &str, age_secs: u64) -> PathBuf {
        let path = dir.join(folder).join("a".repeat(FILE_NAME_LEN));
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        let file = fs::File::create(&path).unwrap();
        file.set_modified(SystemTime::now() - Duration::from_secs(age_secs))
            .unwrap();
        path
    }

    #[test]
    fn prune_keeps_the_most_recently_used_files() {
        let dir = scratch_dir("prune");
        let newest = cache_file(&dir, "01", 10);
        let second = cache_file(&dir, "02", 20);
        let third = cache_file(&dir, "03", 30);
        let old = cache_file(&dir, "04", 400);
        let older = cache_file(&dir, "05", 500);

        prune(&dir, 3);

        assert!(newest.exists() && second.exists() && third.exists());
        assert!(!old.exists() && !older.exists());
    }

    #[test]
    fn prune_leaves_other_caches_alone() {
        let dir = scratch_dir("others");
        for folder in ["01", "02", "03"] {
            cache_file(&dir, folder, 10);
        }
        let image = dir.join("image_cache").join("a".repeat(FILE_NAME_LEN));
        fs::create_dir_all(image.parent().unwrap()).unwrap();
        fs::write(&image, b"image").unwrap();
        let short = dir.join("04").join("short");
        fs::create_dir_all(short.parent().unwrap()).unwrap();
        fs::write(&short, b"not audio").unwrap();

        prune(&dir, 3);

        assert!(image.exists() && short.exists());
    }

    #[test]
    fn touching_a_file_saves_it_from_pruning() {
        let dir = scratch_dir("touch");
        let replayed = cache_file(&dir, "01", 900);
        for (folder, age) in [("02", 10), ("03", 20), ("04", 30)] {
            cache_file(&dir, folder, age);
        }

        touch(&replayed);
        prune(&dir, 3);

        assert!(replayed.exists());
        assert!(!dir.join("04").join("a".repeat(FILE_NAME_LEN)).exists());
    }
}
