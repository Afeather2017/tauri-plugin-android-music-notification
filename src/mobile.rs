use serde::de::DeserializeOwned;
use tauri::{
    plugin::{PluginApi, PluginHandle},
    AppHandle, Runtime,
};

use crate::models::*;

pub struct MusicNotificationState;

impl Default for MusicNotificationState {
    fn default() -> Self {
        Self
    }
}

#[cfg(target_os = "ios")]
tauri::ios_plugin_binding!(init_plugin_music_notification);

// initializes the Kotlin or Swift plugin classes
pub fn init<R: Runtime, C: DeserializeOwned>(
    _app: &AppHandle<R>,
    api: PluginApi<R, C>,
) -> crate::Result<MusicNotification<R>> {
    #[cfg(target_os = "android")]
    let handle =
        api.register_android_plugin("com.plugin.music_notification", "MusicNotificationPlugin")?;
    #[cfg(target_os = "ios")]
    let handle = api.register_ios_plugin(init_plugin_music_notification)?;
    Ok(MusicNotification(handle, MusicNotificationState::default()))
}

/// Access to the music-notification APIs.
pub struct MusicNotification<R: Runtime>(PluginHandle<R>, MusicNotificationState);

// Command forwarding to the Kotlin plugin. One macro per call shape; the
// command list mirrors `build.rs`.
macro_rules! forward {
    // No request payload, `EmptyResponse` result.
    ($fn_name:ident, $method:literal) => {
        pub fn $fn_name(&self) -> crate::Result<EmptyResponse> {
            self.0
                .run_mobile_plugin($method, EmptyRequest {})
                .map_err(Into::into)
        }
    };
    // No request payload, custom response type.
    ($fn_name:ident, $method:literal -> $response:ty) => {
        pub fn $fn_name(&self) -> crate::Result<$response> {
            self.0
                .run_mobile_plugin($method, EmptyRequest {})
                .map_err(Into::into)
        }
    };
    // Typed request payload, `EmptyResponse` result.
    ($fn_name:ident, $method:literal, $request:ty) => {
        pub fn $fn_name(&self, payload: $request) -> crate::Result<EmptyResponse> {
            self.0
                .run_mobile_plugin($method, payload)
                .map_err(Into::into)
        }
    };
    // Typed request payload, custom response type.
    ($fn_name:ident, $method:literal, $request:ty, $response:ty) => {
        pub fn $fn_name(&self, payload: $request) -> crate::Result<$response> {
            self.0
                .run_mobile_plugin($method, payload)
                .map_err(Into::into)
        }
    };
}

impl<R: Runtime> MusicNotification<R> {
    forward!(ping, "ping", PingRequest, PingResponse);
    forward!(play, "play", PlayRequest, PlayResponse);
    forward!(pause, "pause");
    forward!(pause_after, "pauseAfter", PauseAfterRequest);
    forward!(resume, "resume");
    forward!(stop, "stop");
    forward!(next, "next");
    forward!(previous, "previous");
    forward!(
        play_track_at_index,
        "playTrackAtIndex",
        PlayTrackAtIndexRequest
    );
    forward!(get_state, "getState" -> PlaybackState);
    forward!(
        set_playing_queue,
        "setPlayingQueue",
        SetPlayingQueueRequest,
        QueueMutationResponse
    );
    forward!(get_playback_session, "getPlaybackSession" -> PlaybackSession);
    forward!(clear_playing_queue, "clearPlayingQueue" -> QueueMutationResponse);
    forward!(
        set_play_mode,
        "setPlayMode",
        SetPlayModeRequest,
        QueueMutationResponse
    );
    forward!(start_service, "startService");
    forward!(stop_service, "stopService");
    forward!(set_volume, "setVolume", SetVolumeRequest, SetVolumeResponse);
    forward!(
        set_normalization_config,
        "setNormalizationConfig",
        SetNormalizationConfigRequest,
        SetNormalizationConfigResponse
    );
    forward!(
        set_headset_media_button_disabled,
        "setHeadsetMediaButtonDisabled",
        SetHeadsetMediaButtonDisabledRequest
    );

    pub fn seek(&self, position: i64, auto_play: bool) -> crate::Result<EmptyResponse> {
        #[derive(serde::Serialize)]
        #[serde(rename_all = "camelCase")]
        struct SeekRequest {
            position: i64,
            auto_play: bool,
        }
        self.0
            .run_mobile_plugin(
                "seek",
                SeekRequest {
                    position,
                    auto_play,
                },
            )
            .map_err(Into::into)
    }

    pub fn seek_and_play(&self, position: i64) -> crate::Result<EmptyResponse> {
        #[derive(serde::Serialize)]
        struct SeekAndPlayRequest {
            position: i64,
        }
        self.0
            .run_mobile_plugin("seekAndPlay", SeekAndPlayRequest { position })
            .map_err(Into::into)
    }

    pub fn set_server(&self, library_name: String) -> crate::Result<SetServerResponse> {
        self.0
            .run_mobile_plugin("setServer", SetServerRequest { library_name })
            .map_err(Into::into)
    }
}
