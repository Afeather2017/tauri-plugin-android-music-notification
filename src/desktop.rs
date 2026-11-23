use serde::de::DeserializeOwned;
use tauri::{plugin::PluginApi, AppHandle, Runtime};

use crate::models::*;

pub struct MusicNotificationState;

impl Default for MusicNotificationState {
    fn default() -> Self {
        Self
    }
}

pub fn init<R: Runtime, C: DeserializeOwned>(
    app: &AppHandle<R>,
    _api: PluginApi<R, C>,
) -> crate::Result<MusicNotification<R>> {
    Ok(MusicNotification(app.clone(), MusicNotificationState))
}

/// Access to the music-notification APIs.
///
/// Playback is Android-only; every command resolves to an explicit
/// "unavailable" response so callers can distinguish it from success.
pub struct MusicNotification<R: Runtime>(AppHandle<R>, MusicNotificationState);

fn unavailable<T: DeserializeOwned>(message: &str) -> T {
    serde_json::from_value(serde_json::json!({ "success": false, "message": message }))
        .expect("static desktop stub payload deserializes")
}

macro_rules! stub {
    // No request payload.
    ($fn_name:ident, $response:ty, $msg:expr) => {
        pub fn $fn_name(&self) -> crate::Result<$response> {
            Ok(unavailable($msg))
        }
    };
    // Typed request payload (kept for signature parity with mobile.rs).
    ($fn_name:ident, $request:ty, $response:ty, $msg:expr) => {
        pub fn $fn_name(&self, _payload: $request) -> crate::Result<$response> {
            Ok(unavailable($msg))
        }
    };
}

const PLAYBACK_ONLY: &str = "Android playback only available on mobile";

impl<R: Runtime> MusicNotification<R> {
    pub fn ping(&self, payload: PingRequest) -> crate::Result<PingResponse> {
        Ok(PingResponse {
            value: payload.value,
        })
    }

    stub!(play, PlayRequest, PlayResponse, PLAYBACK_ONLY);
    stub!(pause, EmptyResponse, PLAYBACK_ONLY);
    stub!(pause_after, PauseAfterRequest, EmptyResponse, PLAYBACK_ONLY);
    stub!(resume, EmptyResponse, PLAYBACK_ONLY);
    stub!(stop, EmptyResponse, PLAYBACK_ONLY);
    stub!(next, EmptyResponse, PLAYBACK_ONLY);
    stub!(previous, EmptyResponse, PLAYBACK_ONLY);
    stub!(
        play_track_at_index,
        PlayTrackAtIndexRequest,
        EmptyResponse,
        PLAYBACK_ONLY
    );
    stub!(
        set_playing_queue,
        SetPlayingQueueRequest,
        QueueMutationResponse,
        PLAYBACK_ONLY
    );
    stub!(clear_playing_queue, QueueMutationResponse, PLAYBACK_ONLY);
    stub!(
        set_play_mode,
        SetPlayModeRequest,
        QueueMutationResponse,
        PLAYBACK_ONLY
    );
    stub!(start_service, EmptyResponse, PLAYBACK_ONLY);
    stub!(stop_service, EmptyResponse, PLAYBACK_ONLY);
    stub!(
        set_volume,
        SetVolumeRequest,
        SetVolumeResponse,
        "Volume control only available on mobile"
    );
    stub!(
        set_normalization_config,
        SetNormalizationConfigRequest,
        SetNormalizationConfigResponse,
        "Normalization config only available on mobile"
    );

    pub fn set_server(&self, _library_name: String) -> crate::Result<SetServerResponse> {
        Ok(unavailable("Server registration only available on mobile"))
    }

    pub fn seek(&self, _position: i64, _auto_play: bool) -> crate::Result<EmptyResponse> {
        Ok(unavailable(PLAYBACK_ONLY))
    }

    pub fn seek_and_play(&self, _position: i64) -> crate::Result<EmptyResponse> {
        Ok(unavailable(PLAYBACK_ONLY))
    }

    pub fn get_state(&self) -> crate::Result<PlaybackState> {
        Ok(PlaybackState {
            is_playing: false,
            position: 0,
            duration: 0,
        })
    }

    pub fn get_playback_session(&self) -> crate::Result<PlaybackSession> {
        Ok(PlaybackSession {
            queue: PlayingQueue {
                songs: Vec::new(),
                current_index: None,
            },
            runtime: PlaybackRuntime {
                is_playing: false,
                position_ms: 0,
                duration_ms: 0,
                status: Some("idle".to_string()),
            },
            play_mode: PlayMode::Sequential,
            current_song_id: None,
        })
    }

    pub fn set_headset_media_button_disabled(
        &self,
        _payload: SetHeadsetMediaButtonDisabledRequest,
    ) -> crate::Result<EmptyResponse> {
        Ok(EmptyResponse { success: true })
    }
}
