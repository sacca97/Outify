//! Keeps the player of a lost Connect session playing until a new one takes over.
//!
//! When the connection drops, the Connect task ends and takes the player down
//! with it. What the player already downloaded is enough to play the current
//! track to its end, so a rebuild keeps the player alive and the new runtime
//! takes over once it is active.

use super::*;

/// How long after the runtime went away a pause is taken to come from its shutdown.
const SHUTDOWN_PAUSE_WINDOW: Duration = Duration::from_secs(3);

struct OfflinePlayer {
    player: Arc<Player>,
    /// Whether playback should go on once the new runtime takes over.
    resume_playing: bool,
    /// The track played to its end and the player has nothing loaded anymore.
    finished: bool,
    /// Shutting the Connect task down pauses the player. A pause before this
    /// moment is that one and not the user's.
    shutdown_pause_until: Instant,
}

/// The player of the current runtime. The runtime's Connect task owns it too.
static LIVE_PLAYER: OnceCell<Mutex<Option<Arc<Player>>>> = OnceCell::new();
static OFFLINE_PLAYER: OnceCell<Mutex<Option<OfflinePlayer>>> = OnceCell::new();

fn live_player() -> std::sync::MutexGuard<'static, Option<Arc<Player>>> {
    LIVE_PLAYER
        .get_or_init(|| Mutex::new(None))
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn offline_player() -> std::sync::MutexGuard<'static, Option<OfflinePlayer>> {
    OFFLINE_PLAYER
        .get_or_init(|| Mutex::new(None))
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Remembers the player of the runtime that was just built.
pub(super) fn remember(player: &Arc<Player>) {
    *live_player() = Some(Arc::clone(player));
}

/// Called when a runtime goes away. A player that was playing stays alive and
/// keeps reporting to the UI, any other one is dropped and stops.
pub(super) fn hold_player(was_playing: bool) {
    let Some(player) = live_player().take() else {
        return;
    };
    let Some(rt) = crate::TOKIO_RUNTIME.get() else {
        return;
    };
    if !was_playing {
        // Dropping the last handle waits for the player thread, not on an async worker.
        rt.spawn_blocking(move || drop(player));
        return;
    }

    let mut events = player.get_player_event_channel();
    let previous = offline_player().replace(OfflinePlayer {
        player,
        resume_playing: true,
        finished: false,
        shutdown_pause_until: Instant::now() + SHUTDOWN_PAUSE_WINDOW,
    });
    if let Some(previous) = previous {
        rt.spawn_blocking(move || drop(previous.player));
    }
    info!("keeping the player alive without a Connect session");

    rt.spawn(async move {
        while let Some(event) = events.recv().await {
            follow(event);
        }
    });
}

/// Mirrors what the offline player does to the shared state and the UI.
fn follow(event: PlayerEvent) {
    // The new runtime took over, what the old player reports is stale.
    if offline_player().is_none() {
        return;
    }

    match event {
        PlayerEvent::Paused { .. } if undo_shutdown_pause() => {}
        PlayerEvent::EndOfTrack { .. }
        | PlayerEvent::Stopped { .. }
        | PlayerEvent::Unavailable { .. } => {
            info!("offline player has nothing left to play");
            if let Some(offline) = offline_player().as_mut() {
                offline.resume_playing = false;
                offline.finished = true;
            }
            IS_PLAYING.store(false, Ordering::Relaxed);
            record_position(0);
            crate::jni_utils::playback::on_player_status(false);
        }
        event => {
            match &event {
                PlayerEvent::Paused { .. } => set_resume_playing(false),
                PlayerEvent::Playing { .. } => set_resume_playing(true),
                PlayerEvent::TrackChanged { .. } => set_finished(false),
                _ => {}
            }
            handle_event(event);
        }
    }
}

/// Records whether the offline player is playing, for the hand-over.
fn set_resume_playing(playing: bool) {
    if let Some(offline) = offline_player().as_mut() {
        offline.resume_playing = playing;
    }
}

fn set_finished(finished: bool) {
    if let Some(offline) = offline_player().as_mut() {
        offline.finished = finished;
    }
}

/// Loads the track the offline player stopped on again, from `position_ms`.
fn reload(offline: &mut OfflinePlayer, start_playing: bool, position_ms: u32) -> bool {
    let Some(uri) = crate::spirc::current_track().and_then(|uri| SpotifyUri::from_uri(&uri).ok())
    else {
        return false;
    };
    offline.finished = false;
    offline.resume_playing = start_playing;
    offline.player.load(uri, start_playing, position_ms);
    true
}

/// Plays on after the pause that comes with shutting the Connect task down.
/// Returns whether the pause was that one.
fn undo_shutdown_pause() -> bool {
    let mut guard = offline_player();
    let Some(offline) = guard.as_mut() else {
        return false;
    };
    if Instant::now() >= offline.shutdown_pause_until {
        return false;
    }
    offline.shutdown_pause_until = Instant::now();
    offline.player.play();
    true
}

/// Pauses the offline player. Returns false if there is none.
pub fn pause() -> bool {
    let mut guard = offline_player();
    let Some(offline) = guard.as_mut() else {
        return false;
    };
    offline.resume_playing = false;
    offline.shutdown_pause_until = Instant::now();
    offline.player.pause();
    true
}

/// Resumes the offline player. Returns false if there is none.
pub fn play() -> bool {
    let mut guard = offline_player();
    let Some(offline) = guard.as_mut() else {
        return false;
    };
    offline.shutdown_pause_until = Instant::now();
    if offline.finished {
        // What was downloaded is still in the cache, so the track can start over.
        return reload(offline, true, 0);
    }
    offline.resume_playing = true;
    offline.player.play();
    true
}

/// Moves the offline player within the current track. Returns false if there is none.
pub fn seek(position_ms: u32) -> bool {
    let mut guard = offline_player();
    let Some(offline) = guard.as_mut() else {
        return false;
    };
    if offline.finished {
        return reload(offline, false, position_ms);
    }
    offline.player.seek(position_ms);
    true
}

/// Toggles the offline player. Returns false if there is none.
pub fn play_pause() -> bool {
    if IS_PLAYING.load(Ordering::Relaxed) {
        pause()
    } else {
        play()
    }
}

/// Lets the new runtime continue where the offline player is now.
pub(super) async fn take_over() {
    let Some(offline) = offline_player().take() else {
        return;
    };

    // A player that is still playing moved on since its last report.
    if IS_PLAYING.load(Ordering::Relaxed) {
        record_position(estimated_position());
    }
    IS_PLAYING.store(offline.resume_playing, Ordering::Relaxed);
    info!(
        "handing over from the offline player (playing: {})",
        offline.resume_playing
    );

    stop(offline.player).await;
}

/// Stops the offline player for good, for a deliberate shutdown.
pub(super) async fn drop_player() {
    let Some(offline) = offline_player().take() else {
        return;
    };
    stop(offline.player).await;
}

/// Dropping the last handle waits for the player thread, so it must not happen
/// on an async worker. The old sink is closed before the new one opens.
async fn stop(player: Arc<Player>) {
    player.stop();
    let _ = tokio::task::spawn_blocking(move || drop(player)).await;
}
